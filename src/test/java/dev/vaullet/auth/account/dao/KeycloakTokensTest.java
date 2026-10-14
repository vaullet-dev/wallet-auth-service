package dev.vaullet.auth.account.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vaullet.auth.support.StubServer;
import dev.vaullet.auth.support.StubServer.Response;
import dev.vaullet.common.error.exception.UpstreamUnavailableException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The client credentials grant against a stand-in token endpoint: what is asked, what is kept, and
 * which answers are worth waiting for.
 *
 * <p>The classification matters on both of this class's paths. On the relay's, it is the metric's
 * {@code reason} — {@code unavailable} says it mends itself, {@code refused} that a person must act.
 * On the Admin API's, it is the caller's status: {@link UpstreamUnavailableException} is a 503, and
 * the {@link IllegalStateException} of a refusal is a 500 with the reason in the log.
 */
class KeycloakTokensTest {

    private static final String REALM = "vaullet";
    private static final String CLIENT = "wallet-auth-service";
    private static final String TOKEN_PATH = "/realms/" + REALM + "/protocol/openid-connect/token";

    private final AtomicInteger issued = new AtomicInteger();
    private final StubServer keycloak = new StubServer(request -> request.path().equals(TOKEN_PATH)
            ? Response.json(200, """
                    {"access_token": "token-%d", "token_type": "Bearer", "expires_in": 300}"""
                    .formatted(issued.incrementAndGet()))
            : Response.json(404, "{}"));

    @AfterEach
    void stop() {
        keycloak.close();
    }

    private KeycloakTokens tokens() {
        return tokens(keycloak.url());
    }

    /** Built as KeycloakUsers and LedgerWallets build theirs: the JDK client, which follows no redirect. */
    private KeycloakTokens tokens(String url) {
        RestClient rest = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
                .baseUrl(url)
                .build();
        return new KeycloakTokens(rest, REALM, CLIENT, "test-only-secret", "ledger:write");
    }

    private void answering(int status, String body) {
        keycloak.answer(request -> Response.json(status, body));
    }

    @Test
    @DisplayName("asks with the client's credentials and the extra scope, and keeps the token while it is fresh")
    void fetches_and_keeps() {
        KeycloakTokens tokens = tokens();

        assertThat(tokens.bearer()).isEqualTo("token-1");
        assertThat(tokens.bearer()).isEqualTo("token-1");

        assertThat(keycloak.requests()).singleElement().satisfies(request -> assertThat(request.body())
                .isEqualTo("grant_type=client_credentials&client_id=wallet-auth-service"
                        + "&client_secret=test-only-secret&scope=ledger%3Awrite"));
    }

    @Nested
    @DisplayName("invalidate")
    class Invalidate {

        @Test
        @DisplayName("drops the token it is shown, so the next caller fetches a new one")
        void drops_it() {
            KeycloakTokens tokens = tokens();
            String refused = tokens.bearer();

            tokens.invalidate(refused);

            assertThat(tokens.bearer()).isEqualTo("token-2");
            assertThat(keycloak.requests()).hasSize(2);
        }

        @Test
        @DisplayName("leaves a token alone that has already replaced the one shown: one refusal, one new token")
        void compare_and_clear() {
            KeycloakTokens tokens = tokens();
            String first = tokens.bearer();
            tokens.invalidate(first);
            String second = tokens.bearer();

            // A second caller refused with the first token reports it late.
            tokens.invalidate(first);
            tokens.invalidate("never-issued");

            assertThat(tokens.bearer()).isEqualTo(second);
            assertThat(keycloak.requests()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("refused: the same answer tomorrow, so a person has to act")
    class Refused {

        @Test
        @DisplayName("400 and 401 refuse the credentials, with Keycloak's own reason")
        void credentials() {
            answering(400, """
                    {"error": "invalid_scope", "error_description": "Invalid scopes: ledger:write"}""");

            assertThatThrownBy(() -> tokens().bearer())
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(UpstreamUnavailableException.class)
                    .hasMessage("Keycloak refused the client credentials of '" + CLIENT + "' in realm '" + REALM
                            + "' (HTTP 400 invalid_scope: Invalid scopes: ledger:write)");

            answering(401, """
                    {"error": "unauthorized_client", "error_description": "Invalid client or Invalid client credentials"}""");
            assertThatThrownBy(() -> tokens().bearer())
                    .isNotInstanceOf(UpstreamUnavailableException.class)
                    .hasMessageContaining("(HTTP 401 unauthorized_client: Invalid client or Invalid client credentials)");
        }

        @Test
        @DisplayName("a realm that is not there is a 404, and says which")
        void no_such_realm() {
            // Keycloak's own answer for an unknown realm.
            answering(404, """
                    {"error": "Realm does not exist", "error_description": "For more on this error consult the server log."}""");

            assertThatThrownBy(() -> tokens().bearer())
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(UpstreamUnavailableException.class)
                    .hasMessageStartingWith("Keycloak's token endpoint answered HTTP 404 Realm does not exist")
                    .hasMessageContaining("realm '" + REALM + "'")
                    .hasMessageContaining("KEYCLOAK_URL");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(ints = {403, 404, 405, 410, 302})
        @DisplayName("every other 4xx but 408 and 429, and a redirect: a URL or a realm that is wrong")
        void configuration(int status) {
            answering(status, "");

            assertThatThrownBy(() -> tokens().bearer())
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(UpstreamUnavailableException.class)
                    .hasMessageStartingWith("Keycloak's token endpoint answered HTTP " + status + " for client '"
                            + CLIENT + "'");
        }
    }

    @Nested
    @DisplayName("not now: retried, and a 503 for the Admin API's caller")
    class NotNow {

        @ParameterizedTest(name = "{0}")
        @ValueSource(ints = {408, 429, 500, 502, 503, 504})
        @DisplayName("408, 429 and 5xx")
        void unavailable(int status) {
            answering(status, "");

            assertThatThrownBy(() -> tokens().bearer())
                    .isInstanceOf(UpstreamUnavailableException.class)
                    .hasMessage("Upstream 'keycloak' is unavailable")
                    .hasRootCauseMessage("HTTP " + status + " from Keycloak's token endpoint");
        }

        @Test
        @DisplayName("and nothing listening at all")
        void not_there() throws IOException {
            int closed;
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                closed = socket.getLocalPort();
            }

            assertThatThrownBy(() -> tokens("http://127.0.0.1:" + closed).bearer())
                    .isInstanceOf(UpstreamUnavailableException.class);
        }
    }
}
