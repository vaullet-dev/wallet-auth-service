package dev.vaullet.auth.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vaullet.auth.account.dao.AccountRepository;
import dev.vaullet.auth.account.dao.AccountRepository.AccountRow;
import dev.vaullet.auth.config.AuthProperties;
import dev.vaullet.auth.outbox.dao.LedgerWallets;
import dev.vaullet.auth.outbox.dao.OutboxRepository;
import dev.vaullet.auth.support.StubServer;
import dev.vaullet.auth.support.StubServer.Request;
import dev.vaullet.auth.support.StubServer.Response;
import dev.vaullet.common.test.IntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The relay against PostgreSQL, with stand-ins on the wire for the ledger's {@code POST /v1/wallets}
 * and Keycloak's token endpoint ({@link StubServer}): hermetic, and free to answer what a real ledger
 * would only answer on a bad day.
 *
 * <p>The relays here are built by hand, as many as a test needs, each a replica of its own with its
 * own token cache — {@code auth.outbox.relay.enabled} is off in the {@code test} profile, so no
 * scheduled relay runs behind a test's back. Each round is run directly with
 * {@link OutboxRelay#deliverDue()}. The entries come from the same two calls the services make, an
 * account insert and {@link Outbox#accountCreated}, in one transaction.
 *
 * <p>The outbox and the accounts are truncated before each test, so every entry a relay meets, and
 * every account its sweep finds, is that test's own.
 */
@IntegrationTest
class OutboxRelayIT {

    private static final String REALM = "vaullet";
    private static final String CLIENT_ID = "wallet-auth-service";
    private static final String CLIENT_SECRET = "test-only-secret";
    private static final String TOKEN_PATH = "/realms/" + REALM + "/protocol/openid-connect/token";

    @Autowired
    private OutboxRepository repository;

    @Autowired
    private Outbox outbox;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private final AtomicInteger tokensIssued = new AtomicInteger();
    private StubServer keycloak;
    private StubServer ledger;

    @BeforeEach
    void start() {
        // Accounts too: the sweep reads every account, and other tests leave theirs behind.
        jdbc.execute("TRUNCATE identity_schema.outbox, identity_schema.accounts");
        keycloak = new StubServer(this::issueToken);
        ledger = new StubServer(OutboxRelayIT::openWallet);
    }

    @AfterEach
    void stop() {
        keycloak.close();
        ledger.close();
    }

    // --- The stand-ins ----------------------------------------------------------------------------

    /** Client credentials always succeed, and every token is a new one: stub-token-1, -2, … */
    private Response issueToken(Request request) {
        if (!request.path().equals(TOKEN_PATH)) {
            return Response.json(404, "{}");
        }
        return Response.json(200, """
                {"access_token": "stub-token-%d", "token_type": "Bearer", "expires_in": 300}"""
                .formatted(tokensIssued.incrementAndGet()));
    }

    /** The ledger on a good day: 201 with the wallet, opened now or found. */
    private static Response openWallet(Request request) {
        if (!request.method().equals("POST") || !request.path().equals("/v1/wallets")) {
            return Response.problem(404, "RESOURCE_NOT_FOUND");
        }
        return Response.json(201, """
                {"wallet_id": "%s", "account_id": "%s", "type": "%s", "status": "ACTIVE",
                 "created_at": "2026-10-14T10:00:00Z", "updated_at": "2026-10-14T10:00:00Z"}"""
                .formatted(UUID.randomUUID(), field(request.body(), "account_id"), field(request.body(), "type")));
    }

    /** The ledger's answer for one type of wallet, and the good-day answer for the other. */
    private static Function<Request, Response> answering(String type, Function<Request, Response> answer) {
        return request -> type.equals(field(request.body(), "type")) ? answer.apply(request) : openWallet(request);
    }

    // --- Helpers -----------------------------------------------------------------------------------

    /** A replica: its own LedgerWallets, and so its own token cache, against the stand-ins. */
    private OutboxRelay relay(SimpleMeterRegistry meters) {
        return relay(meters, ledger.url());
    }

    private OutboxRelay relay(SimpleMeterRegistry meters, String ledgerUrl) {
        AuthProperties properties = new AuthProperties(
                AuthProperties.Provider.LOCAL,
                new AuthProperties.Keycloak(keycloak.url(), REALM, CLIENT_ID, CLIENT_SECRET, Duration.ofSeconds(2)),
                new AuthProperties.UserManagement(Duration.ofHours(72)),
                new AuthProperties.Ledger(ledgerUrl, Duration.ofSeconds(2)),
                new AuthProperties.Outbox(new AuthProperties.Outbox.Relay(
                        true, Duration.ofSeconds(5), 10, Duration.ofMinutes(1),
                        Duration.ofSeconds(10), Duration.ofMinutes(15), Duration.ofMinutes(10))));
        return new OutboxRelay(repository, outbox, new LedgerWallets(properties), properties.outbox().relay(), meters,
                System::nanoTime);
    }

    /** An account and its entry, written the way both services write them: together. */
    private UUID anAccount() {
        UUID id = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            AccountRow row = accounts.insert(id, null, "acme-" + id, "ACTIVE");
            outbox.accountCreated(row.getAccountId(), row.getCreatedAt());
        });
        return id;
    }

    private Map<String, Object> entryOf(UUID accountId) {
        return jdbc.queryForMap("""
                SELECT attempts, last_error, delivered_at, extract(epoch FROM next_attempt_at - now()) AS due_in_seconds
                  FROM identity_schema.outbox WHERE account_id = ?""", accountId);
    }

    private void dueNow(UUID accountId) {
        jdbc.update("UPDATE identity_schema.outbox SET next_attempt_at = now() WHERE account_id = ?", accountId);
    }

    /** Rounds until one claims nothing — what the schedule does, without the waiting. */
    private static int drain(OutboxRelay relay) {
        int claimed = 0;
        for (int round = 0; round < 100; round++) {
            int now = relay.deliverDue();
            if (now == 0) {
                return claimed;
            }
            claimed += now;
        }
        throw new AssertionError("Still claiming after 100 rounds");
    }

    private List<String> walletsAskedFor(UUID accountId) {
        return ledger.requests().stream()
                .filter(request -> accountId.toString().equals(field(request.body(), "account_id")))
                .map(request -> field(request.body(), "type"))
                .toList();
    }

    private static @Nullable String field(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Map<String, String> form(String body) {
        return Arrays.stream(body.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }

    private static double counted(SimpleMeterRegistry meters, String name, String... tags) {
        return meters.get(name).tags(tags).counter().count();
    }

    // --- Delivering --------------------------------------------------------------------------------

    @Test
    @DisplayName("opens REAL then BONUS, with a ledger:write token of the service's own client, and the entry is done")
    void delivers() {
        UUID account = anAccount();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        assertThat(relay(meters).deliverDue()).isEqualTo(1);

        // Client credentials of wallet-auth-service — the Admin API's client and secret — with the
        // optional scope the ledger checks asked for on top.
        assertThat(keycloak.requests()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo(TOKEN_PATH);
            assertThat(form(request.body()))
                    .containsEntry("grant_type", "client_credentials")
                    .containsEntry("client_id", CLIENT_ID)
                    .containsEntry("client_secret", CLIENT_SECRET)
                    .containsEntry("scope", "ledger:write");
        });
        // The contract of POST /v1/wallets: snake_case JSON, one call per type, REAL first.
        assertThat(walletsAskedFor(account)).containsExactly("REAL", "BONUS");
        assertThat(ledger.requests()).allSatisfy(request -> {
            assertThat(request.path()).isEqualTo("/v1/wallets");
            assertThat(request.header("Authorization")).isEqualTo("Bearer stub-token-1");
            assertThat(request.header("Content-Type")).startsWith("application/json");
            assertThat(request.body()).isEqualTo(
                    "{\"account_id\":\"" + account + "\",\"type\":\"" + field(request.body(), "type") + "\"}");
        });

        Map<String, Object> entry = entryOf(account);
        assertThat(entry.get("delivered_at")).isNotNull();
        assertThat(entry).containsEntry("attempts", 1);
        assertThat(entry.get("last_error")).isNull();
        assertThat(counted(meters, "auth.outbox.delivered")).isEqualTo(1);
        // Done is done: nothing left for the next round.
        assertThat(relay(meters).deliverDue()).isZero();
    }

    @Test
    @DisplayName("keeps its token until it is about to expire, rather than fetching one per call")
    void reuses_the_token() {
        List<UUID> created = List.of(anAccount(), anAccount(), anAccount());

        drain(relay(new SimpleMeterRegistry()));

        assertThat(ledger.requests()).hasSize(2 * created.size());
        assertThat(keycloak.requests()).hasSize(1);
    }

    @Test
    @DisplayName("retries after a failure, after the backoff, until both wallets exist")
    void retries_after_a_failure() {
        UUID account = anAccount();
        AtomicBoolean busyOnce = new AtomicBoolean(true);
        ledger.answer(answering("REAL", request -> busyOnce.getAndSet(false)
                ? Response.problem(503, "ACCOUNT_BUSY")
                : openWallet(request)));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        OutboxRelay relay = relay(meters);

        assertThat(relay.deliverDue()).isEqualTo(1);

        Map<String, Object> failed = entryOf(account);
        assertThat(failed.get("delivered_at")).isNull();
        assertThat(failed).containsEntry("attempts", 1);
        assertThat((String) failed.get("last_error"))
                .contains("Upstream 'ledger' is unavailable")
                .contains("HTTP 503 ACCOUNT_BUSY")
                .contains("POST /v1/wallets REAL");
        // The first backoff: due again in ten seconds, and not before.
        assertThat(((Number) failed.get("due_in_seconds")).doubleValue()).isBetween(8.0, 10.5);
        assertThat(relay.deliverDue()).isZero();
        assertThat(counted(meters, "auth.outbox.failed", "reason", "unavailable")).isEqualTo(1);

        dueNow(account);
        assertThat(relay.deliverDue()).isEqualTo(1);

        Map<String, Object> delivered = entryOf(account);
        assertThat(delivered.get("delivered_at")).isNotNull();
        assertThat(delivered).containsEntry("attempts", 2);
        // Kept: it is why this entry took two attempts.
        assertThat((String) delivered.get("last_error")).contains("ACCOUNT_BUSY");
        // REAL asked twice — the ledger answers a repeat with the wallet it has — and BONUS once.
        assertThat(walletsAskedFor(account)).containsExactly("REAL", "REAL", "BONUS");
    }

    @Test
    @DisplayName("takes 409 WALLET_CLOSED as done: the account had the wallet, and closing is terminal")
    void closed_wallet_is_done() {
        UUID account = anAccount();
        ledger.answer(answering("REAL", request -> Response.problem(409, "WALLET_CLOSED")));

        relay(new SimpleMeterRegistry()).deliverDue();

        assertThat(entryOf(account).get("delivered_at")).isNotNull();
        assertThat(walletsAskedFor(account)).containsExactly("REAL", "BONUS");
    }

    @Test
    @DisplayName("takes both wallets closed as done too")
    void both_closed_is_done() {
        UUID account = anAccount();
        ledger.answer(request -> Response.problem(409, "WALLET_CLOSED"));

        relay(new SimpleMeterRegistry()).deliverDue();

        assertThat(entryOf(account).get("delivered_at")).isNotNull();
    }

    // --- Failing, and waiting ---------------------------------------------------------------------

    @Test
    @DisplayName("a token the ledger will not accept is 'refused': the entry waits, and the error names the scope")
    void token_not_accepted() {
        UUID account = anAccount();
        ledger.answer(request -> Response.problem(403, "ACCESS_DENIED"));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        relay(meters).deliverDue();

        Map<String, Object> entry = entryOf(account);
        assertThat(entry.get("delivered_at")).isNull();
        assertThat(entry).containsEntry("attempts", 1);
        assertThat((String) entry.get("last_error"))
                .contains("HTTP 403 ACCESS_DENIED")
                .contains("client '" + CLIENT_ID + "'")
                .contains("ledger:write");
        assertThat(counted(meters, "auth.outbox.failed", "reason", "refused")).isEqualTo(1);
    }

    @Test
    @DisplayName("a token the ledger answers 401 to is dropped: the next attempt fetches another, and gets through")
    void a_401_drops_the_token() {
        UUID account = anAccount();
        // The first token is no good to the ledger — revoked, say, or signed with a key since rotated.
        // A bare 401, as Spring Security's resource server sends it.
        ledger.answer(request -> "Bearer stub-token-1".equals(request.header("Authorization"))
                ? Response.json(401, "")
                : openWallet(request));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        OutboxRelay relay = relay(meters);

        relay.deliverDue();
        assertThat(entryOf(account).get("delivered_at")).isNull();
        assertThat((String) entryOf(account).get("last_error")).contains("HTTP 401");
        assertThat(counted(meters, "auth.outbox.failed", "reason", "refused")).isEqualTo(1);

        dueNow(account);
        relay.deliverDue();

        // Kept, the token would be refused until it expired, 300 s after it was issued here. Dropped,
        // the next attempt asked Keycloak for another.
        assertThat(keycloak.requests()).hasSize(2);
        assertThat(ledger.requests()).extracting(request -> request.header("Authorization"))
                .containsExactly("Bearer stub-token-1", "Bearer stub-token-2", "Bearer stub-token-2");
        assertThat(entryOf(account).get("delivered_at")).isNotNull();
    }

    @Test
    @DisplayName("a 201 that is not the wallet asked for — LEDGER_URL naming something else — never marks the entry done")
    void a_201_from_something_else() {
        UUID account = anAccount();
        // Another service, or a gateway's catch-all, that answers any POST with 201.
        ledger.answer(request -> Response.json(201, """
                {"id": 42, "status": "created"}"""));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        relay(meters).deliverDue();

        Map<String, Object> entry = entryOf(account);
        assertThat(entry.get("delivered_at")).isNull();
        assertThat((String) entry.get("last_error"))
                .startsWith("Unexpected HTTP 201 from the ledger on POST /v1/wallets REAL")
                .contains("not the wallet asked for");
        // An error, every attempt, until someone corrects the address.
        assertThat(counted(meters, "auth.outbox.failed", "reason", "refused")).isEqualTo(1);
        assertThat(counted(meters, "auth.outbox.delivered")).isZero();
    }

    @Test
    @DisplayName("a scope Keycloak will not grant is 'refused' before the ledger is called, with Keycloak's reason")
    void scope_not_offered() {
        UUID account = anAccount();
        // What Keycloak answers before gitops#30: the client does not offer ledger:write.
        keycloak.answer(request -> Response.json(400, """
                {"error": "invalid_scope", "error_description": "Invalid scopes: ledger:write"}"""));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        relay(meters).deliverDue();

        assertThat((String) entryOf(account).get("last_error"))
                .contains("Keycloak refused the client credentials of '" + CLIENT_ID + "'")
                .contains("invalid_scope: Invalid scopes: ledger:write");
        assertThat(ledger.requests()).isEmpty();
        assertThat(counted(meters, "auth.outbox.failed", "reason", "refused")).isEqualTo(1);
    }

    @Test
    @DisplayName("a ledger that is not there is 'unavailable': the entry waits — the state until the ledger is deployed")
    void ledger_not_there() throws Exception {
        UUID account = anAccount();
        // A port nothing listens on: taken, then let go.
        int closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = socket.getLocalPort();
        }
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        relay(meters, "http://127.0.0.1:" + closed).deliverDue();

        Map<String, Object> entry = entryOf(account);
        assertThat(entry.get("delivered_at")).isNull();
        assertThat((String) entry.get("last_error")).startsWith("Upstream 'ledger' is unavailable");
        assertThat(counted(meters, "auth.outbox.failed", "reason", "unavailable")).isEqualTo(1);
    }

    // --- The sweep ---------------------------------------------------------------------------------

    @Test
    @DisplayName("the sweep queues an account that was created without its entry, and the next round opens its wallets")
    void sweep_then_deliver() {
        // What the previous version leaves behind while a release rolls out: accounts, no entries.
        UUID left = UUID.randomUUID();
        accounts.insert(left, null, "acme-" + left, "ACTIVE");
        UUID closed = UUID.randomUUID();
        accounts.insert(closed, null, "acme-" + closed, "CLOSED");
        UUID queued = anAccount();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        OutboxRelay relay = relay(meters);

        // Only the account that queued its own entry is due before the sweep...
        assertThat(relay.deliverDue()).isEqualTo(1);
        assertThat(walletsAskedFor(left)).isEmpty();

        // ...and the sweep finds the one that did not, but not the closed one.
        assertThat(relay.queueMissing()).isEqualTo(1);
        assertThat(counted(meters, "auth.outbox.swept")).isEqualTo(1);
        assertThat(relay.deliverDue()).isEqualTo(1);

        assertThat(walletsAskedFor(left)).containsExactly("REAL", "BONUS");
        assertThat(entryOf(left).get("delivered_at")).isNotNull();
        assertThat(walletsAskedFor(closed)).isEmpty();
        assertThat(walletsAskedFor(queued)).containsExactly("REAL", "BONUS");
        // Found once: the next sweep has nothing left to queue.
        assertThat(relay.queueMissing()).isZero();
    }

    // --- Several replicas ---------------------------------------------------------------------------

    @Test
    @DisplayName("an entry another relay has claimed is left alone until its lease ends")
    void the_lease_keeps_other_relays_off() {
        UUID account = anAccount();
        // Another replica claims it — and, say, dies before recording anything.
        assertThat(repository.claimDue(10, Duration.ofMinutes(1))).hasSize(1);
        OutboxRelay relay = relay(new SimpleMeterRegistry());

        assertThat(relay.deliverDue()).isZero();
        assertThat(ledger.requests()).isEmpty();

        // The lease ends, and with it the claim: the entry is anyone's again.
        dueNow(account);
        assertThat(relay.deliverDue()).isEqualTo(1);
        assertThat(entryOf(account).get("delivered_at")).isNotNull();
    }

    @Test
    @DisplayName("two relays running at once never both deliver one entry")
    void two_relays_at_once() throws Exception {
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            created.add(anAccount());
        }
        // Each relay fetches its own token, so the ledger can tell them apart by it. It holds every
        // call until it has seen both — so both relays have certainly claimed a batch and are
        // delivering at the same time — and then answers slowly, to keep them overlapping.
        Set<String> callers = ConcurrentHashMap.newKeySet();
        CountDownLatch bothCalling = new CountDownLatch(1);
        ledger.answer(request -> {
            callers.add(String.valueOf(request.header("Authorization")));
            if (callers.size() >= 2) {
                bothCalling.countDown();
            }
            try {
                bothCalling.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return openWallet(request).after(Duration.ofMillis(5));
        });
        SimpleMeterRegistry firstMeters = new SimpleMeterRegistry();
        SimpleMeterRegistry secondMeters = new SimpleMeterRegistry();
        OutboxRelay first = relay(firstMeters);
        OutboxRelay second = relay(secondMeters);
        CyclicBarrier start = new CyclicBarrier(2);

        try (ExecutorService replicas = Executors.newFixedThreadPool(2)) {
            Future<Integer> one = replicas.submit(() -> {
                start.await();
                return drain(first);
            });
            Future<Integer> other = replicas.submit(() -> {
                start.await();
                return drain(second);
            });
            one.get(60, TimeUnit.SECONDS);
            other.get(60, TimeUnit.SECONDS);
        }

        assertThat(bothCalling.getCount()).as("both relays reached the ledger").isZero();
        // Every account asked for each wallet exactly once, between the two of them.
        assertThat(created).allSatisfy(account ->
                assertThat(walletsAskedFor(account)).containsExactlyInAnyOrder("REAL", "BONUS"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM identity_schema.outbox WHERE delivered_at IS NOT NULL AND attempts = 1",
                Integer.class)).isEqualTo(created.size());
        double byFirst = counted(firstMeters, "auth.outbox.delivered");
        double bySecond = counted(secondMeters, "auth.outbox.delivered");
        assertThat(byFirst + bySecond).isEqualTo(created.size());
        assertThat(byFirst).isPositive();
        assertThat(bySecond).isPositive();
    }
}
