package dev.vaullet.auth.account.dao;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vaullet.common.error.UpstreamUnavailableException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The access token this service presents to Keycloak's Admin API: obtained with the client
 * credentials grant, and reused until shortly before it expires.
 *
 * <p><b>A {@link ReentrantLock}, not {@code synchronized}.</b> The platform runs on virtual threads,
 * and on Java 21 a virtual thread that blocks inside a {@code synchronized} block pins its carrier
 * thread for the length of the call. Fetching a token is exactly such a call, so the lock that stops
 * a burst of requests from each fetching their own token is one that parks instead of pinning.
 *
 * <p>The form body is encoded by hand rather than handed to a form converter. It is three fields,
 * and a {@code String} body goes through the one converter every {@link RestClient} is certain to
 * have.
 */
final class KeycloakTokens {

    /** Renew this long before expiry, so a token never expires between being read and being used. */
    private static final Duration MARGIN = Duration.ofSeconds(30);

    private final RestClient rest;
    private final String realm;
    private final String clientId;
    private final String form;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile @Nullable Token current;

    KeycloakTokens(RestClient rest, String realm, String clientId, String clientSecret) {
        this.rest = rest;
        this.realm = realm;
        this.clientId = clientId;
        this.form = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
    }

    /** A token valid for at least {@link #MARGIN} more, fetching a new one only when it has to. */
    String bearer() {
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
                            // Wrong secret, or the client has service accounts switched off. Nothing
                            // the caller can fix and nothing a retry will fix, so not a 503.
                            throw new IllegalStateException("Keycloak refused the client credentials of '"
                                    + clientId + "' in realm '" + realm + "' (HTTP " + status + ")");
                        }
                        throw new UpstreamUnavailableException("keycloak",
                                new IllegalStateException("HTTP " + status + " from Keycloak's token endpoint"));
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
