package dev.vaullet.auth.account.dao;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vaullet.common.error.exception.UpstreamUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * An access token of this service's own client, obtained with the client credentials grant, and
 * reused until shortly before it expires. One instance per use: the Admin API's token, and the
 * outbox relay's token for the ledger, which asks for {@code ledger:write} on top.
 *
 * <p><b>A {@link ReentrantLock}, not {@code synchronized}.</b> The platform runs on virtual threads,
 * and on Java 21 a virtual thread that blocks inside a {@code synchronized} block pins its carrier
 * thread for the length of the call. Fetching a token is exactly such a call, so the lock that stops
 * a burst of requests from each fetching their own token is one that parks instead of pinning.
 *
 * <p>The form body is encoded by hand rather than handed to a form converter. It is three or four
 * fields, and a {@code String} body goes through the one converter every {@link RestClient} is
 * certain to have.
 *
 * <p>Public, though it is a DAO detail, because the relay's ledger client in {@code outbox.dao} uses
 * it too: one client, one secret, one way of getting its tokens.
 */
public final class KeycloakTokens {

    /** Renew this long before expiry, so a token never expires between being read and being used. */
    private static final Duration MARGIN = Duration.ofSeconds(30);

    private static final Pattern ERROR = Pattern.compile("\"error\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern ERROR_DESCRIPTION = Pattern.compile("\"error_description\"\\s*:\\s*\"([^\"]*)\"");

    private final RestClient rest;
    private final String realm;
    private final String clientId;
    private final String form;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile @Nullable Token current;

    /**
     * @param rest a client whose base URL is Keycloak's, relative path included
     * @param scope the scopes to ask for beyond the client's defaults, space-separated, or null for
     *     none. A scope the client offers only as <em>optional</em> is in the token only if asked
     *     for, which is how {@code ledger:write} stays out of the Admin API's token.
     */
    public KeycloakTokens(RestClient rest, String realm, String clientId, String clientSecret, @Nullable String scope) {
        this.rest = rest;
        this.realm = realm;
        this.clientId = clientId;
        this.form = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                + (scope == null ? "" : "&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8));
    }

    /** A token valid for at least {@link #MARGIN} more, fetching a new one only when it has to. */
    public String bearer() {
        Token token = current;
        if (token != null && token.isFresh()) {
            return token.value();
        }
        lock.lock();
        try {
            token = current;
            if (token == null || !token.isFresh()) {
                token = fetch();
                current = token;
            }
            return token.value();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Forget this token, if it is still the one held: whoever it was shown to would not accept it —
     * expired early, revoked, signed with a key since rotated — and keeping it until it ages out
     * would fail every call until then. The next {@link #bearer()} fetches another.
     *
     * <p>Compare-and-clear: a token that another caller has already replaced is not thrown away in its
     * place, so a burst of refusals of one token costs one new token, not one each.
     */
    public void invalidate(String token) {
        lock.lock();
        try {
            Token held = current;
            if (held != null && held.value().equals(token)) {
                current = null;
            }
        } finally {
            lock.unlock();
        }
    }

    private Token fetch() {
        TokenResponse response;
        try {
            response = rest.post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((request, reply) -> {
                        int status = reply.getStatusCode().value();
                        if (status == 200) {
                            return reply.bodyTo(TokenResponse.class);
                        }
                        if (status == 400 || status == 401) {
                            // Wrong secret, the client has service accounts switched off, or it does
                            // not offer a scope asked for. Nothing the caller can fix and nothing a
                            // retry will fix, so not a 503 — and Keycloak's own reason, because the
                            // three need three different fixes.
                            throw new IllegalStateException("Keycloak refused the client credentials of '"
                                    + clientId + "' in realm '" + realm + "' (HTTP " + status + reason(reply) + ")");
                        }
                        if (status == 408 || status == 429 || status >= 500) {
                            // The only answers that say "not now": a timeout, a rate limit, a server
                            // unwell. Retried, and the Admin API's caller gets a 503.
                            throw new UpstreamUnavailableException("keycloak",
                                    new IllegalStateException("HTTP " + status + " from Keycloak's token endpoint"));
                        }
                        // Everything else is the same tomorrow: a 404 for a realm or a path that is
                        // not there — KEYCLOAK_URL without Keycloak's relative path is the usual one —
                        // a 403 from whatever stands in front of Keycloak, a redirect. Filed as
                        // unavailable it would be retried quietly, under a reason that says it mends
                        // itself; it is configuration, and says so.
                        throw new IllegalStateException("Keycloak's token endpoint answered HTTP " + status
                                + reason(reply) + " for client '" + clientId + "' in realm '" + realm + "'. Not a "
                                + "refusal a retry will change: check KEYCLOAK_URL, Keycloak's relative path "
                                + "included, and the realm.");
                    });
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("keycloak", e);
        }
        if (response == null || response.accessToken() == null) {
            throw new IllegalStateException("Keycloak's token endpoint answered 200 without an access_token");
        }
        Instant renewAt = Instant.now().plusSeconds(response.expiresIn()).minus(MARGIN);
        return new Token(response.accessToken(), renewAt);
    }

    /**
     * OAuth's {@code error} and {@code error_description}, such as {@code invalid_scope: Invalid
     * scopes: ledger:write}. Never the whole body, and never anything from the request.
     */
    private static String reason(ClientHttpResponse reply) {
        try (InputStream in = reply.getBody()) {
            String body = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
            Matcher error = ERROR.matcher(body);
            if (!error.find()) {
                return "";
            }
            Matcher description = ERROR_DESCRIPTION.matcher(body);
            return " " + error.group(1) + (description.find() ? ": " + description.group(1) : "");
        } catch (IOException unreadable) {
            return "";
        }
    }

    private record Token(String value, Instant renewAt) {

        boolean isFresh() {
            return Instant.now().isBefore(renewAt);
        }

        @Override
        public String toString() {
            return "Token[renewAt=" + renewAt + "]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(
            @JsonProperty("access_token") @Nullable String accessToken,
            @JsonProperty("expires_in") long expiresIn) {

        @Override
        public String toString() {
            return "TokenResponse[expiresIn=" + expiresIn + "]";
        }
    }
}
