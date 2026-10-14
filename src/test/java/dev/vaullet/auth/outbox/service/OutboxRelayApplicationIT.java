package dev.vaullet.auth.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zaxxer.hikari.HikariDataSource;
import dev.vaullet.auth.AuthApplication;
import dev.vaullet.auth.support.StubServer;
import dev.vaullet.auth.support.StubServer.Request;
import dev.vaullet.auth.support.StubServer.Response;
import dev.vaullet.common.test.IntegrationTest;
import dev.vaullet.common.test.TestSecurityConfiguration;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The relay as the application runs it — switched on, built by Spring, scheduled — which
 * {@code OutboxRelayIT}, building its relays by hand, cannot show: that {@code OutboxRelaySchedule}
 * is registered and its {@code @EnableScheduling} takes, that Spring builds the relay through its
 * {@code @Autowired} constructor, and that rounds run with nobody calling them. And that with the
 * relay on and no ledger to deliver to, the application does not start at all.
 *
 * <p>Its own application context: the relay on, polling every 200 ms, pointed at stand-ins
 * ({@link StubServer}) for the ledger and Keycloak's token endpoint. Closed after the class
 * ({@code @DirtiesContext}), so its scheduler stops with it — left in the context cache, it would go
 * on polling the shared database under every test class that runs after this one.
 */
@IntegrationTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxRelayApplicationIT {

    private static final String TOKEN_PATH = "/realms/vaullet/protocol/openid-connect/token";
    private static final Pattern ACCOUNT_ID = Pattern.compile("\"account_id\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern TYPE = Pattern.compile("\"type\"\\s*:\\s*\"([^\"]*)\"");

    private static final AtomicInteger TOKENS = new AtomicInteger();
    private static final StubServer KEYCLOAK = new StubServer(request -> request.path().equals(TOKEN_PATH)
            ? Response.json(200, """
                    {"access_token": "stub-token-%d", "token_type": "Bearer", "expires_in": 300}"""
                    .formatted(TOKENS.incrementAndGet()))
            : Response.json(404, "{}"));
    private static final StubServer LEDGER = new StubServer(OutboxRelayApplicationIT::openWallet);

    @DynamicPropertySource
    static void relayOn(DynamicPropertyRegistry registry) {
        registry.add("auth.outbox.relay.enabled", () -> "true");
        registry.add("auth.outbox.relay.poll-interval", () -> "200ms");
        registry.add("auth.ledger.url", LEDGER::url);
        // The relay's token endpoint. The Admin API's client is pointed here too, and never called:
        // the account below is created without an identity.
        registry.add("auth.keycloak.url", KEYCLOAK::url);
        registry.add("auth.keycloak.client-secret", () -> "test-only-secret");
    }

    @AfterAll
    static void stopStandIns() {
        KEYCLOAK.close();
        LEDGER.close();
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void clean() {
        // What earlier test classes left in the shared database would be queued — by the sweep, at
        // this context's start — and delivered ahead of this test's entry. Emptied, it is the only one.
        jdbc.execute("TRUNCATE identity_schema.outbox, identity_schema.accounts");
    }

    /** The ledger on a good day: 201 with the wallet asked for. */
    private static Response openWallet(Request request) {
        if (!request.method().equals("POST") || !request.path().equals("/v1/wallets")) {
            return Response.problem(404, "RESOURCE_NOT_FOUND");
        }
        return Response.json(201, """
                {"wallet_id": "%s", "account_id": "%s", "type": "%s", "status": "ACTIVE"}"""
                .formatted(UUID.randomUUID(), field(ACCOUNT_ID, request.body()), field(TYPE, request.body())));
    }

    private static @Nullable String field(Pattern pattern, String json) {
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** The entry, once delivered: what the schedule does, waited for rather than called. */
    private Map<String, Object> awaitDelivered(UUID account, Duration atMost) throws InterruptedException {
        long deadline = System.nanoTime() + atMost.toNanos();
        while (true) {
            List<Map<String, Object>> entry = jdbc.queryForList(
                    "SELECT attempts, delivered_at, last_error FROM identity_schema.outbox WHERE account_id = ?", account);
            if (!entry.isEmpty() && entry.getFirst().get("delivered_at") != null) {
                return entry.getFirst();
            }
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("Not delivered within " + atMost + ": " + entry);
            }
            Thread.sleep(100);
        }
    }

    @Test
    @DisplayName("an account created through the API gets its wallets from the scheduled relay, with nobody calling it")
    void delivers_on_its_own() throws Exception {
        String location = mvc.perform(post("/v1/accounts")
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority("SCOPE_identity:admin"),
                                new SimpleGrantedAuthority("SCOPE_identity:read"),
                                new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"external_ref\": \"relay-on-%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        UUID account = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

        // A round every 200 ms: ten seconds is for a slow machine, not for the relay.
        Map<String, Object> entry = awaitDelivered(account, Duration.ofSeconds(10));

        assertThat(entry).containsEntry("attempts", 1);
        assertThat(entry.get("last_error")).isNull();
        List<Request> calls = LEDGER.requests().stream()
                .filter(request -> account.toString().equals(field(ACCOUNT_ID, request.body())))
                .toList();
        assertThat(calls).extracting(request -> field(TYPE, request.body())).containsExactly("REAL", "BONUS");
        assertThat(calls).allSatisfy(request ->
                assertThat(request.header("Authorization")).startsWith("Bearer stub-token-"));
        // The token the Spring-built relay asked for: its own client's, with ledger:write.
        assertThat(KEYCLOAK.requests()).isNotEmpty().allSatisfy(request ->
                assertThat(request.body()).contains("client_id=wallet-auth-service").contains("scope=ledger%3Awrite"));
    }

    @Test
    @DisplayName("with the relay on and no LEDGER_URL, the application refuses to start")
    void refuses_to_start_without_the_ledger() throws SQLException {
        // The same application, started as TestAuthApplication starts it, against this test's database
        // and with the test profile's settings, and LEDGER_URL left empty — an argument outranks
        // whatever the environment holds.
        HikariDataSource database = dataSource.unwrap(HikariDataSource.class);

        assertThatThrownBy(() -> SpringApplication.from(AuthApplication::main)
                        .with(TestSecurityConfiguration.class)
                        .run("--spring.profiles.active=test",
                                "--spring.datasource.url=" + database.getJdbcUrl(),
                                "--spring.datasource.username=" + database.getUsername(),
                                "--spring.datasource.password=" + database.getPassword(),
                                "--server.port=0",
                                "--auth.outbox.relay.enabled=true",
                                "--auth.ledger.url="))
                .hasStackTraceContaining("ledgerWallets")
                .hasStackTraceContaining("auth.ledger.url must be set while the outbox relay is on");
    }
}
