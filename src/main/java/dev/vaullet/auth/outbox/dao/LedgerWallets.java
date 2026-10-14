package dev.vaullet.auth.outbox.dao;

import dev.vaullet.auth.account.dao.KeycloakTokens;
import dev.vaullet.auth.config.AuthProperties;
import dev.vaullet.common.error.exception.UpstreamUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The ledger's {@code POST /v1/wallets}, for the outbox relay: open an account's wallet of one type.
 *
 * <p>A DAO in the sense {@code KeycloakUsers} is one: it knows the wire format and the status codes,
 * and takes no decisions. What a closed wallet means for an outbox entry, and how long to wait after
 * a refusal, are {@code OutboxRelay}'s. Nothing HTTP-shaped leaves this class: callers get an
 * {@link Opening}, a ledger or Keycloak that did not answer arrives as
 * {@link UpstreamUnavailableException}, and an answer that a retry will not change — a refused
 * token, a request the ledger calls invalid — as {@link IllegalStateException}.
 *
 * <p><b>The contract</b> (wallet-ledger-service README, "The API" and "Wallets"; its
 * {@code WalletController}): a JSON body {@code {"account_id", "type"}} and a bearer token carrying
 * {@code ledger:write}. {@code 201} with the wallet whether this call opened it or an earlier one
 * did, because an account has at most one wallet of each type — which is what makes a repeated
 * delivery harmless. {@code 409 WALLET_CLOSED} when that wallet was closed: closing is terminal, so
 * there is nothing left to open.
 *
 * <p><b>Only that {@code 201} is a yes,</b> and only when its body is the wallet asked for: the
 * account and the type sent. An entry marked delivered is never delivered again, so a
 * {@code LEDGER_URL} that names something else — another service, a gateway's default page — must
 * fail every attempt, loudly, rather than mark entries done with no wallet behind them.
 *
 * <p><b>The token</b> is this service's own client's, {@code wallet-auth-service} — the client and
 * secret the Admin API already uses — with the optional scope {@code ledger:write} asked for on top.
 * It is a {@link KeycloakTokens} of its own, so its cache and its scope stay apart from the Admin
 * API's, whose token keeps only the client's defaults. A {@code 401} drops it from that cache: the
 * ledger would not accept it, so the next attempt asks Keycloak for another.
 *
 * <p>Only registered while the relay is on. With it off this service needs neither the ledger's
 * address nor a token for it, and does not ask for them.
 */
@Repository
@ConditionalOnProperty(name = "auth.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class LedgerWallets {

    /** What the ledger checks on every write (wallet-ledger-service README, "Security"). */
    public static final String SCOPE = "ledger:write";

    private static final String UPSTREAM = "ledger";
    private static final String WALLET_CLOSED = "WALLET_CLOSED";
    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern DETAIL = Pattern.compile("\"detail\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern ACCOUNT_ID = Pattern.compile("\"account_id\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern TYPE = Pattern.compile("\"type\"\\s*:\\s*\"([^\"]*)\"");

    /**
     * The wallet types every account gets, in the order they are opened: {@code REAL} first, the
     * one money is deposited into.
     */
    public enum WalletType {
        REAL,
        BONUS
    }

    /** The ledger's yes, in either of its two forms. */
    public enum Opening {
        /** The wallet exists: this call opened it, or an earlier one did. */
        OPENED,
        /** The wallet existed and was closed, which is terminal. There is nothing left to open. */
        CLOSED
    }

    private final RestClient ledger;
    private final KeycloakTokens tokens;
    private final String clientId;
    private final Duration longestOpen;

    /** Public so an integration test can point one at stand-ins for the ledger and Keycloak. */
    public LedgerWallets(AuthProperties properties) {
        AuthProperties.Keycloak keycloak = properties.keycloak();
        AuthProperties.Ledger settings = properties.ledger();
        String ledgerUrl = required(settings.url(), "auth.ledger.url");
        String keycloakUrl = required(keycloak.url(), "auth.keycloak.url");
        String secret = required(keycloak.clientSecret(), "auth.keycloak.client-secret");
        this.clientId = keycloak.clientId();
        this.tokens = new KeycloakTokens(client(keycloakUrl, keycloak.timeout()), keycloak.realm(), clientId, secret,
                SCOPE);
        this.ledger = client(ledgerUrl, settings.timeout());
        // A token may have to be fetched first, and each call is bounded by its connect timeout and
        // then its read timeout, separately.
        this.longestOpen = keycloak.timeout().plus(keycloak.timeout()).plus(settings.timeout()).plus(settings.timeout());
    }

    /**
     * Open the account's wallet of this type, or find the one already open.
     *
     * @throws UpstreamUnavailableException the ledger or Keycloak did not answer, or answered that it
     *     cannot now — a retry later may well succeed
     * @throws IllegalStateException the ledger or Keycloak refused, or answered something nobody
     *     expected — a person has to look before a retry can succeed
     */
    public Opening open(UUID accountId, WalletType type) {
        String token = tokens.bearer();
        // Built by hand, like KeycloakTokens' form: two values that need no escaping, a UUID and an
        // enum constant, and the snake_case the ledger reads (ADR-011 §8) is on the page rather than
        // in a naming strategy this client deliberately does not carry.
        String body = "{\"account_id\":\"" + accountId + "\",\"type\":\"" + type.name() + "\"}";
        try {
            return ledger.post()
                    .uri("/v1/wallets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(token))
                    .body(body)
                    .exchangeForRequiredValue((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401) {
                            tokens.invalidate(token);
                        }
                        return interpret(status, head(response), accountId, type, clientId);
                    });
        } catch (RestClientException e) {
            // Refused, reset, timed out, or a name that does not resolve: the ledger is not there,
            // which until it is deployed is the expected answer (wallet-ledger-service#12).
            throw new UpstreamUnavailableException(UPSTREAM, e);
        }
    }

    /**
     * The longest one {@link #open} can take: a token fetched first, then the call, each bounded by
     * its connect timeout and its read timeout. The relay's lease is checked against it.
     */
    public Duration longestOpen() {
        return longestOpen;
    }

    /**
     * What one answer from {@code POST /v1/wallets} means. Static and given only what the wire
     * carried, so {@code LedgerWalletsTest} pins every case without a ledger.
     *
     * <ul>
     *   <li>{@code 201} whose body names the account and the type asked for: the wallet exists.
     *       Nothing else is that: not a {@code 200}, which the ledger does not send, and not a
     *       {@code 201} about anything else.
     *   <li>{@code 409 WALLET_CLOSED}: closed, which is terminal; nothing to open.
     *   <li>{@code 401}, {@code 403}: the token is not one the ledger accepts — the scope is missing,
     *       or the ledger trusts another issuer. Configuration, so not the 503 kind of failure.
     *   <li>{@code 429} and {@code 5xx}, {@code 503 ACCOUNT_BUSY} among them: not now.
     *   <li>Anything else, {@code 400 VALIDATION_FAILED} and a {@code 409} of another kind included:
     *       the two services disagree about the contract, or the URL names something else.
     * </ul>
     *
     * @param body the start of the answer's body: the wallet, or a problem document
     * @param accountId the account asked for, which a {@code 201} must name
     * @param type the type asked for, which a {@code 201} must name
     */
    static Opening interpret(int status, String body, UUID accountId, WalletType type, String clientId) {
        String call = "POST /v1/wallets " + type;
        if (status == 201) {
            @Nullable String account = match(ACCOUNT_ID, body);
            @Nullable String typeAnswered = match(TYPE, body);
            if (accountId.toString().equalsIgnoreCase(account) && type.name().equals(typeAnswered)) {
                return Opening.OPENED;
            }
            throw new IllegalStateException("Unexpected HTTP 201 from the ledger on " + call + ": its body names "
                    + "account " + shown(account) + " and type " + shown(typeAnswered) + ", not the wallet asked for. "
                    + "Is LEDGER_URL the ledger's address?");
        }
        @Nullable String code = match(CODE, body);
        @Nullable String detail = match(DETAIL, body);
        if (status == 409 && WALLET_CLOSED.equals(code)) {
            return Opening.CLOSED;
        }
        String answer = "HTTP " + status + (code == null ? "" : " " + code) + (detail == null ? "" : " (" + detail + ")");
        if (status == 401 || status == 403) {
            throw new IllegalStateException("The ledger refused " + call + " with " + answer + ". The token comes "
                    + "from client '" + clientId + "' and asks for " + SCOPE + ": the client must offer " + SCOPE
                    + " as an optional client scope, and the ledger must trust the issuer it came from.");
        }
        if (status == 429 || status >= 500) {
            throw new UpstreamUnavailableException(UPSTREAM,
                    new IllegalStateException(answer + " from the ledger on " + call));
        }
        throw new IllegalStateException("Unexpected " + answer + " from the ledger on " + call);
    }

    private static RestClient client(String url, Duration timeout) {
        // HTTP/1.1 explicitly, for the reason KeycloakUsers gives: no h2c upgrade attempt on every
        // connection to a plain-http in-cluster Service.
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(timeout);
        // RestClient.builder(), not Boot's: nothing here needs a JSON mapper, and the ledger's wire
        // format is written out above rather than left to one.
        return RestClient.builder()
                .requestFactory(requests)
                .baseUrl(url.endsWith("/") ? url.substring(0, url.length() - 1) : url)
                .build();
    }

    /** Whatever a field held, short enough for last_error, or that it was not there. */
    private static String shown(@Nullable String value) {
        if (value == null) {
            return "(none)";
        }
        return value.length() <= 64 ? "'" + value + "'" : "'" + value.substring(0, 63) + "…'";
    }

    /** The start of the body: a wallet, or a problem document. Never more: it is read for two fields. */
    private static String head(ClientHttpResponse response) {
        try (InputStream in = response.getBody()) {
            return new String(in.readNBytes(4096), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return "";
        }
    }

    private static @Nullable String match(Pattern field, String json) {
        Matcher matcher = field.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String required(@Nullable String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must be set while the outbox relay is on "
                    + "(auth.outbox.relay.enabled)");
        }
        return value;
    }
}
