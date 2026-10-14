package dev.vaullet.auth.outbox.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vaullet.auth.config.AuthProperties;
import dev.vaullet.auth.outbox.dao.LedgerWallets.Opening;
import dev.vaullet.auth.outbox.dao.LedgerWallets.WalletType;
import dev.vaullet.common.error.exception.UpstreamUnavailableException;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What each answer of the ledger's {@code POST /v1/wallets} means to the relay, without a ledger.
 *
 * <p>Three outcomes and nothing else: the wallet is there, it was closed, or the attempt failed —
 * and a failure is either one that time fixes ({@link UpstreamUnavailableException}) or one a person
 * has to ({@link IllegalStateException}). {@code OutboxRelayIT} sends the same answers over the wire.
 */
class LedgerWalletsTest {

    private static final UUID ACCOUNT = UUID.fromString("dddddddd-0000-4000-8000-000000000001");
    private static final String CALL = "POST /v1/wallets REAL";
    private static final String CLIENT = "wallet-auth-service";

    /** The ledger's answer, with this body, to opening ACCOUNT's REAL wallet. */
    private static Opening answered(int status, String body) {
        return LedgerWallets.interpret(status, body, ACCOUNT, WalletType.REAL, CLIENT);
    }

    /** A problem document with this code, as every Vaullet service sends one; no body for none. */
    private static Opening problem(int status, @Nullable String code) {
        return answered(status, code == null ? "" : """
                {"type": "https://docs.vaullet.dev/errors/stub", "status": %d, "code": "%s", "detail": "stub detail"}"""
                .formatted(status, code));
    }

    /** The ledger's WalletResponse, snake_case, as its WalletController sends it. */
    private static String wallet(String accountId, String type) {
        return """
                {"wallet_id":"0b9f3c1e-5d2a-4f8b-9c3d-7e6f5a4b3c2d","account_id":"%s","type":"%s","status":"ACTIVE",\
                "created_at":"2026-10-14T10:00:00Z","updated_at":"2026-10-14T10:00:00Z"}""".formatted(accountId, type);
    }

    @Nested
    @DisplayName("the wallet is there")
    class Done {

        @Test
        @DisplayName("201 with the wallet asked for, whether this call opened it or an earlier one did")
        void created() {
            assertThat(answered(201, wallet(ACCOUNT.toString(), "REAL"))).isEqualTo(Opening.OPENED);
        }

        @Test
        @DisplayName("...read as JSON is written, not as one string: spacing, key order and the id's case are free")
        void read_by_field() {
            assertThat(answered(201, """
                    { "type" : "REAL", "status": "FROZEN", "account_id" : "%s" }"""
                    .formatted(ACCOUNT.toString().toUpperCase(Locale.ROOT)))).isEqualTo(Opening.OPENED);
        }

        @Test
        @DisplayName("409 WALLET_CLOSED: the account had it, and someone closed it — nothing left to open")
        void closed() {
            assertThat(problem(409, "WALLET_CLOSED")).isEqualTo(Opening.CLOSED);
        }
    }

    @Nested
    @DisplayName("not the wallet asked for — refused, because delivered is final")
    class NotTheWallet {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "{\"account_id\":\"dddddddd-0000-4000-8000-000000000002\",\"type\":\"REAL\"}",
            "{\"account_id\":\"dddddddd-0000-4000-8000-000000000001\",\"type\":\"BONUS\"}",
            "{\"id\":\"42\",\"status\":\"created\"}",
            "<html><body>Created</body></html>",
            "",
        })
        @DisplayName("a 201 that names another account or type, or none")
        void another_201(String body) {
            assertThatThrownBy(() -> answered(201, body))
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(UpstreamUnavailableException.class)
                    .hasMessageStartingWith("Unexpected HTTP 201 from the ledger on " + CALL)
                    .hasMessageContaining("not the wallet asked for")
                    .hasMessageContaining("LEDGER_URL");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(ints = {200, 202, 204})
        @DisplayName("any other 2xx, even with the right wallet in it: the ledger answers 201")
        void other_2xx(int status) {
            assertThatThrownBy(() -> answered(status, wallet(ACCOUNT.toString(), "REAL")))
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(UpstreamUnavailableException.class)
                    .hasMessage("Unexpected HTTP " + status + " from the ledger on " + CALL);
        }

        @Test
        @DisplayName("what it names is kept short in last_error")
        void bounded() {
            assertThatThrownBy(() -> answered(201, "{\"account_id\":\"" + "x".repeat(500) + "\"}"))
                    .hasMessageContaining("'" + "x".repeat(63) + "…'")
                    .hasMessageContaining("type (none)");
        }
    }

    @Nested
    @DisplayName("not now — retried, and expected while the ledger is away")
    class Unavailable {

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
            "503, ACCOUNT_BUSY",
            "503, UPSTREAM_UNAVAILABLE",
            "500, INTERNAL_ERROR",
            "502, ",
            "504, ",
            "429, ",
        })
        @DisplayName("5xx and 429")
        void server_side(int status, @Nullable String code) {
            assertThatThrownBy(() -> problem(status, code))
                    .isInstanceOf(UpstreamUnavailableException.class)
                    .hasRootCauseMessage("HTTP " + status + (code == null ? "" : " " + code + " (stub detail)")
                            + " from the ledger on " + CALL);
        }
    }

    @Nested
    @DisplayName("refused — retried, but a person has to act")
    class Refused {

        @ParameterizedTest(name = "{0}")
        @ValueSource(ints = {401, 403})
        @DisplayName("401 and 403 name the scope the token must carry")
        void token_not_accepted(int status) {
            assertThatThrownBy(() -> problem(status, "ACCESS_DENIED"))
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(UpstreamUnavailableException.class)
                    .hasMessageContaining("refused " + CALL)
                    .hasMessageContaining("HTTP " + status + " ACCESS_DENIED")
                    .hasMessageContaining("client '" + CLIENT + "'")
                    .hasMessageContaining("ledger:write");
        }

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
            "400, VALIDATION_FAILED",
            "409, RESOURCE_CONFLICT",
            "404, RESOURCE_NOT_FOUND",
            "404, ",
            "302, ",
        })
        @DisplayName("anything else is a disagreement about the contract, or a URL naming something else")
        void unexpected(int status, @Nullable String code) {
            assertThatThrownBy(() -> problem(status, code))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("Unexpected HTTP " + status)
                    .hasMessageEndingWith("from the ledger on " + CALL);
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        private AuthProperties properties(@Nullable String ledgerUrl, @Nullable String keycloakUrl, @Nullable String secret) {
            return new AuthProperties(
                    AuthProperties.Provider.LOCAL,
                    new AuthProperties.Keycloak(keycloakUrl, "vaullet", CLIENT, secret, Duration.ofSeconds(5)),
                    new AuthProperties.UserManagement(Duration.ofHours(72)),
                    new AuthProperties.Ledger(ledgerUrl, Duration.ofSeconds(5)),
                    new AuthProperties.Outbox(new AuthProperties.Outbox.Relay(
                            true, Duration.ofSeconds(5), 20, Duration.ofMinutes(2),
                            Duration.ofSeconds(10), Duration.ofMinutes(15), Duration.ofMinutes(10))));
        }

        @Test
        @DisplayName("refuses to be built without the ledger's address, the token endpoint or the secret")
        void needs_its_settings() {
            assertThatThrownBy(() -> new LedgerWallets(properties(null, "http://keycloak", "s")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("auth.ledger.url");
            assertThatThrownBy(() -> new LedgerWallets(properties("http://ledger", " ", "s")))
                    .hasMessageContaining("auth.keycloak.url");
            assertThatThrownBy(() -> new LedgerWallets(properties("http://ledger", "http://keycloak", null)))
                    .hasMessageContaining("auth.keycloak.client-secret");
        }

        @Test
        @DisplayName("one open can take a token's connect and read timeouts, then the ledger's")
        void longest_open() {
            LedgerWallets wallets = new LedgerWallets(properties("http://ledger", "http://keycloak", "s"));

            assertThat(wallets.longestOpen()).isEqualTo(Duration.ofSeconds(20));
        }
    }
}
