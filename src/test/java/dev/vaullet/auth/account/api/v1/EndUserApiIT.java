package dev.vaullet.auth.account.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;

import dev.vaullet.auth.account.dao.KeycloakUsers;
import dev.vaullet.auth.account.dao.KeycloakUsers.NewKeycloakUser;
import dev.vaullet.auth.account.service.EndUserService;
import dev.vaullet.auth.support.FailingOutbox;
import dev.vaullet.auth.support.KeycloakTestContainer;
import dev.vaullet.auth.support.SmtpSink;
import dev.vaullet.common.test.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

/**
 * End users through the whole stack, in local mode: filter chain, authorisation, validation, the
 * rules, PostgreSQL, a real Keycloak, and the mail it sends ({@link SmtpSink}).
 *
 * <p>The realm is shared by every test in the JVM, so each test makes its own username, email and
 * reference. The accounts table is not truncated here for the same reason in reverse: rows in it
 * point at Keycloak users other tests still own.
 */
@IntegrationTest
class EndUserApiIT {

    @DynamicPropertySource
    static void localMode(DynamicPropertyRegistry registry) {
        registry.add("auth.provider", () -> "local");
        registry.add("auth.keycloak.url", KeycloakTestContainer::url);
        registry.add("auth.keycloak.realm", () -> KeycloakTestContainer.REALM);
        registry.add("auth.keycloak.client-id", () -> KeycloakTestContainer.CLIENT_ID);
        registry.add("auth.keycloak.client-secret", () -> KeycloakTestContainer.CLIENT_SECRET);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private KeycloakUsers keycloak;

    @Autowired
    private JdbcTemplate jdbc;

    private static JwtRequestPostProcessor superAdmin() {
        return jwt().authorities(
                new SimpleGrantedAuthority("SCOPE_identity:admin"),
                new SimpleGrantedAuthority("SCOPE_identity:read"),
                new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    }

    private static JwtRequestPostProcessor supportAgent() {
        return jwt().authorities(
                new SimpleGrantedAuthority("SCOPE_identity:read"),
                new SimpleGrantedAuthority("ROLE_SUPPORT_AGENT"));
    }

    private static JwtRequestPostProcessor fraudReviewer() {
        return jwt().authorities(
                new SimpleGrantedAuthority("SCOPE_identity:admin"),
                new SimpleGrantedAuthority("SCOPE_identity:read"),
                new SimpleGrantedAuthority("ROLE_FRAUD_REVIEWER"));
    }

    /** A username, email address and reference nobody else in the realm has. */
    private record Person(String username, String email, String ref) {
        static Person fresh() {
            String tag = UUID.randomUUID().toString().substring(0, 8);
            return new Person("jane-" + tag, "jane-" + tag + "@test.vaullet.dev", "acme-" + tag);
        }
    }

    private static String body(Person person) {
        return body(person, false);
    }

    private static String body(Person person, boolean emailVerified) {
        return """
                {
                  "external_ref": "%s",
                  "identity": {
                    "username": "%s",
                    "email": "%s",
                    "email_verified": %s,
                    "first_name": "Jane",
                    "last_name": "Doe"
                  }
                }""".formatted(person.ref(), person.username(), person.email(), emailVerified);
    }

    /** Creates an end user and returns the account id, from the Location header. */
    private String create(Person person) throws Exception {
        return create(person, false);
    }

    private String create(Person person, boolean emailVerified) throws Exception {
        String location = mvc.perform(post("/v1/accounts").with(superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(person, emailVerified)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        return location.substring(location.lastIndexOf('/') + 1);
    }

    /**
     * Gives the user a password, as following the invitation would: from now on they can sign in.
     * Straight through Keycloak's Admin API with the service's own client, because no endpoint here
     * ever takes a password.
     */
    private void setPassword(String username) {
        UUID id = keycloak.findByUsername(username).orElseThrow().id();
        RestClient client = RestClient.create(KeycloakTestContainer.url());
        String token = JsonPath.read(client.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", KeycloakTestContainer.REALM)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials&client_id=" + KeycloakTestContainer.CLIENT_ID
                        + "&client_secret=" + KeycloakTestContainer.CLIENT_SECRET)
                .retrieve()
                .body(String.class), "$.access_token");
        client.put()
                .uri("/admin/realms/{realm}/users/{id}/reset-password", KeycloakTestContainer.REALM, id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"type": "password", "value": "Signed-in-123", "temporary": false}""")
                .retrieve()
                .toBodilessEntity();
        assertThat(keycloak.canSignIn(id)).isTrue();
    }

    @Nested
    @DisplayName("POST /v1/accounts with an identity")
    class Create {

        @Test
        @DisplayName("creates a linked account, and a Keycloak user carrying its account_id")
        void creates_end_user() throws Exception {
            Person person = Person.fresh();

            String id = create(person);

            mvc.perform(get("/v1/accounts/{id}", id).with(supportAgent()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.external_ref", is(person.ref())))
                    .andExpect(jsonPath("$.linked", is(true)))
                    .andExpect(jsonPath("$.status", is("ACTIVE")));

            // The anchor's primary key is the account_id Keycloak holds: one value, minted once.
            var user = keycloak.findByUsername(person.username()).orElseThrow();
            assertThat(user.accountId()).map(UUID::toString).contains(id);
            assertThat(user.enabled()).isTrue();
        }

        @Test
        @DisplayName("emails the new user Keycloak's link to set their own password")
        void sends_the_invitation() throws Exception {
            Person person = Person.fresh();

            create(person);

            List<SmtpSink.Message> received = SmtpSink.messagesTo(person.email());
            assertThat(received).hasSize(1);
            assertThat(received.getFirst().link()).hasValueSatisfying(link -> assertThat(link).contains("action-token"));
            // No password was set here; the invitation is the only way in.
            assertThat(keycloak.canSignIn(keycloak.findByUsername(person.username()).orElseThrow().id())).isFalse();
        }

        @Test
        @DisplayName("repeating the request returns the same account, and invites again while the user has no password")
        void repeat_is_a_retry() throws Exception {
            Person person = Person.fresh();

            assertThat(create(person)).isEqualTo(create(person));
            assertThat(SmtpSink.messagesTo(person.email())).hasSize(2);
        }

        @Test
        @DisplayName("finishes a create that stopped after Keycloak, with the account id Keycloak holds")
        void finishes_interrupted_create() throws Exception {
            Person person = Person.fresh();
            UUID earlierAccountId = UUID.randomUUID();
            // What an attempt that died between the two systems leaves behind: the user, no anchor.
            keycloak.create(NewKeycloakUser.endUser(
                    person.username(), person.email(), "Jane", "Doe", false, earlierAccountId)).orElseThrow();

            assertThat(create(person)).isEqualTo(earlierAccountId.toString());
            assertThat(SmtpSink.messagesTo(person.email())).hasSize(1);
        }

        @Test
        @DisplayName("queues the account's identity.account-created event with its anchor")
        void queues_the_account_created_event() throws Exception {
            UUID id = UUID.fromString(create(Person.fresh()));

            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM identity_schema.outbox
                     WHERE account_id = ? AND event_type = 'identity.account-created' AND delivered_at IS NULL""",
                    Integer.class, id)).isEqualTo(1);
        }

        @Test
        @DisplayName("an anchor whose event cannot be written is not written either, and the retry finishes the create")
        void no_anchor_without_its_event() throws Exception {
            Person person = Person.fresh();
            FailingOutbox refusing = FailingOutbox.forReference(jdbc, person.ref());
            try {
                mvc.perform(post("/v1/accounts").with(superAdmin())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(person)))
                        .andExpect(status().isInternalServerError());
            } finally {
                refusing.close();
            }

            // Keycloak has the user, born carrying its account id; the anchor and its entry were
            // rolled back together, so neither exists.
            UUID id = keycloak.findByUsername(person.username()).orElseThrow().accountId().orElseThrow();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_schema.accounts WHERE account_id = ?",
                    Integer.class, id)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_schema.outbox WHERE account_id = ?",
                    Integer.class, id)).isZero();

            // The retry is the recovery path: it finishes the create under the same id, event included.
            assertThat(create(person)).isEqualTo(id.toString());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_schema.outbox WHERE account_id = ?",
                    Integer.class, id)).isEqualTo(1);
        }

        @Test
        @DisplayName("refuses a username somebody else has")
        void username_taken() throws Exception {
            Person first = Person.fresh();
            create(first);
            Person second = Person.fresh();
            Person clash = new Person(first.username(), second.email(), second.ref());

            mvc.perform(post("/v1/accounts").with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(clash)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code", is("USERNAME_TAKEN")));
        }

        @Test
        @DisplayName("refuses an email address somebody else has")
        void email_taken() throws Exception {
            Person first = Person.fresh();
            create(first);
            Person second = Person.fresh();
            Person clash = new Person(second.username(), first.email(), second.ref());

            mvc.perform(post("/v1/accounts").with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(clash)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code", is("EMAIL_TAKEN")));
        }

        @Test
        @DisplayName("rejects an identity without an email address, naming the field")
        void requires_email() throws Exception {
            mvc.perform(post("/v1/accounts").with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"external_ref": "acme-no-email", "identity": {"username": "no-email"}}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
        }

        @Test
        @DisplayName("is SUPER_ADMIN's alone")
        void creation_needs_super_admin() throws Exception {
            mvc.perform(post("/v1/accounts").with(fraudReviewer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Person.fresh())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
        }
    }

    /**
     * The account at first sign-in (#24). The caller registered on Keycloak's own pages, so they are
     * a realm user before they are anything here: no {@code account_id}, no role, not staff.
     */
    @Nested
    @DisplayName("GET and PUT /v1/accounts/me")
    class Me {

        /** What registering on Keycloak's page leaves: a user with names and an email, and nothing of ours. */
        private UUID registered(Person person, boolean emailVerified) {
            return keycloak.create(new NewKeycloakUser(person.username(), person.email(), "Jane", "Doe", emailVerified,
                    null, null, false, List.of())).orElseThrow();
        }

        /** Their token: their own sub, and identity:self, which every client people sign in with carries. */
        private JwtRequestPostProcessor signedIn(UUID subject) {
            return jwt().jwt(token -> token.subject(subject.toString()))
                    .authorities(new SimpleGrantedAuthority("SCOPE_identity:self"));
        }

        private String createOwn(UUID subject, int status) throws Exception {
            String body = mvc.perform(put("/v1/accounts/me").with(signedIn(subject)))
                    .andExpect(status().is(status))
                    .andReturn().getResponse().getContentAsString();
            return JsonPath.read(body, "$.account_id");
        }

        private int anchors(UUID subject) {
            return jdbc.queryForObject("SELECT count(*) FROM identity_schema.accounts WHERE keycloak_sub = ?",
                    Integer.class, subject);
        }

        @Test
        @DisplayName("creates the account, queues its event, writes account_id onto the user and grants END_USER")
        void creates_own_account() throws Exception {
            Person person = Person.fresh();
            UUID subject = registered(person, true);

            mvc.perform(put("/v1/accounts/me").with(signedIn(subject)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.linked", is(true)))
                    .andExpect(jsonPath("$.status", is("ACTIVE")))
                    .andExpect(jsonPath("$.external_ref").doesNotExist());

            UUID accountId = jdbc.queryForObject(
                    "SELECT account_id FROM identity_schema.accounts WHERE keycloak_sub = ?", UUID.class, subject);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM identity_schema.outbox
                     WHERE account_id = ? AND event_type = 'identity.account-created'""", Integer.class, accountId))
                    .isEqualTo(1);

            // The attribute the token mapper reads, END_USER, and nothing else of the user changed:
            // the write-back sends the whole user back, because Keycloak has no partial update.
            var user = keycloak.findById(subject).orElseThrow();
            assertThat(user.accountId()).contains(accountId);
            assertThat(keycloak.realmRoles(subject)).contains("END_USER");
            assertThat(user.username()).isEqualTo(person.username());
            assertThat(user.email()).isEqualTo(person.email());
            assertThat(user.firstName()).isEqualTo("Jane");
            assertThat(user.lastName()).isEqualTo("Doe");
            assertThat(user.emailVerified()).isTrue();
            assertThat(user.enabled()).isTrue();

            mvc.perform(get("/v1/accounts/me").with(signedIn(subject)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.account_id", is(accountId.toString())));
        }

        @Test
        @DisplayName("repeated, it answers 200 with the same account and queues nothing more")
        void repeat_is_safe() throws Exception {
            UUID subject = registered(Person.fresh(), true);

            String first = createOwn(subject, 201);
            String second = createOwn(subject, 200);

            assertThat(second).isEqualTo(first);
            assertThat(anchors(subject)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_schema.outbox WHERE account_id = ?",
                    Integer.class, UUID.fromString(first))).isEqualTo(1);
        }

        @Test
        @DisplayName("four calls at once, a double click and then some, still make one account")
        void concurrent_calls() throws Exception {
            UUID subject = registered(Person.fresh(), true);
            int calls = 4;
            var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(calls)) {
                List<Future<String>> answers = new ArrayList<>();
                for (int i = 0; i < calls; i++) {
                    answers.add(pool.submit(() -> {
                        start.await();
                        var response = mvc.perform(put("/v1/accounts/me").with(signedIn(subject)))
                                .andReturn().getResponse();
                        assertThat(response.getStatus()).isIn(200, 201);
                        return JsonPath.<String>read(response.getContentAsString(), "$.account_id");
                    }));
                }
                start.countDown();
                Set<String> ids = new HashSet<>();
                for (var answer : answers) {
                    ids.add(answer.get(30, TimeUnit.SECONDS));
                }
                assertThat(ids).hasSize(1);
            }
            assertThat(anchors(subject)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM identity_schema.outbox o
                      JOIN identity_schema.accounts a ON a.account_id = o.account_id
                     WHERE a.keycloak_sub = ?""", Integer.class, subject)).isEqualTo(1);
        }

        @Test
        @DisplayName("GET 404s until the account exists, without a role to ask with")
        void get_before_put() throws Exception {
            UUID subject = registered(Person.fresh(), true);

            mvc.perform(get("/v1/accounts/me").with(signedIn(subject)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code", is("RESOURCE_NOT_FOUND")));
        }

        @Test
        @DisplayName("an end user created by staff gets their existing account back, unchanged")
        void staff_created_end_user() throws Exception {
            Person person = Person.fresh();
            String id = create(person, true);
            UUID subject = keycloak.findByUsername(person.username()).orElseThrow().id();

            assertThat(createOwn(subject, 200)).isEqualTo(id);
            mvc.perform(get("/v1/accounts/me").with(signedIn(subject)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.external_ref", is(person.ref())));
        }

        @Test
        @DisplayName("refuses a member of staff, and creates nothing")
        void refuses_staff() throws Exception {
            String tag = UUID.randomUUID().toString().substring(0, 8);
            UUID subject = keycloak.create(NewKeycloakUser.staff("ana-" + tag, "ana-" + tag + "@test.vaullet.dev",
                    "Ana", "Kovač", "Temp-pass-123", List.of())).orElseThrow();

            mvc.perform(put("/v1/accounts/me").with(signedIn(subject)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("STAFF_ACCOUNT_FORBIDDEN")));

            assertThat(anchors(subject)).isZero();
            assertThat(keycloak.realmRoles(subject)).doesNotContain("END_USER");
            assertThat(keycloak.findById(subject).orElseThrow().accountId()).isEmpty();
        }

        @Test
        @DisplayName("refuses somebody holding a staff role outside the staff group")
        void refuses_staff_role() throws Exception {
            UUID subject = registered(Person.fresh(), true);
            keycloak.grantRealmRole(subject, "SUPPORT_AGENT");

            mvc.perform(put("/v1/accounts/me").with(signedIn(subject)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("STAFF_ACCOUNT_FORBIDDEN")));
            assertThat(anchors(subject)).isZero();
        }

        @Test
        @DisplayName("refuses an unverified address, and creates nothing")
        void refuses_unverified() throws Exception {
            UUID subject = registered(Person.fresh(), false);

            mvc.perform(put("/v1/accounts/me").with(signedIn(subject)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code", is("EMAIL_NOT_VERIFIED")));
            assertThat(anchors(subject)).isZero();
        }

        @Test
        @DisplayName("refuses a client's service account: it has no email address to have verified")
        void refuses_a_service_account() throws Exception {
            // The user behind a client-credentials token. Keycloak's Admin API does not say a user is
            // a service account, so it is the missing address that refuses it.
            RestClient client = RestClient.create(KeycloakTestContainer.url());
            String token = JsonPath.read(client.post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", KeycloakTestContainer.REALM)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=client_credentials&client_id=" + KeycloakTestContainer.CLIENT_ID
                            + "&client_secret=" + KeycloakTestContainer.CLIENT_SECRET)
                    .retrieve()
                    .body(String.class), "$.access_token");
            String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),
                    StandardCharsets.UTF_8);
            UUID serviceAccount = UUID.fromString(JsonPath.read(payload, "$.sub"));

            mvc.perform(put("/v1/accounts/me").with(signedIn(serviceAccount)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code", is("EMAIL_NOT_VERIFIED")));
            assertThat(anchors(serviceAccount)).isZero();
        }

        @Test
        @DisplayName("needs identity:self; a role does not stand in for it")
        void needs_the_scope() throws Exception {
            UUID subject = registered(Person.fresh(), true);

            mvc.perform(put("/v1/accounts/me").with(jwt().jwt(token -> token.subject(subject.toString()))
                            .authorities(new SimpleGrantedAuthority("SCOPE_identity:admin"),
                                    new SimpleGrantedAuthority("ROLE_END_USER"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
            mvc.perform(get("/v1/accounts/me").with(jwt().jwt(token -> token.subject(subject.toString()))
                            .authorities(new SimpleGrantedAuthority("SCOPE_identity:read"),
                                    new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
                    .andExpect(status().isForbidden());
            assertThat(anchors(subject)).isZero();
        }

        @Test
        @DisplayName("refuses a token that names nobody in Keycloak's terms")
        void needs_a_keycloak_sub() throws Exception {
            mvc.perform(put("/v1/accounts/me").with(jwt().jwt(token -> token.subject("not-a-keycloak-id"))
                            .authorities(new SimpleGrantedAuthority("SCOPE_identity:self"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
        }

        @Test
        @DisplayName("refuses without a token")
        void needs_a_token() throws Exception {
            mvc.perform(put("/v1/accounts/me")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("the method itself takes only the caller's own id, whoever calls it")
        void method_rule_is_the_callers_own(@Autowired EndUserService endUsers) {
            UUID caller = registered(Person.fresh(), true);
            UUID somebodyElse = registered(Person.fresh(), true);
            var token = new JwtAuthenticationToken(
                    Jwt.withTokenValue("test").header("alg", "none")
                            .subject(caller.toString()).build(),
                    List.of(new SimpleGrantedAuthority("SCOPE_identity:self")));
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(token);
            SecurityContextHolder.setContext(context);
            try {
                assertThatThrownBy(() -> endUsers.createOwn(somebodyElse))
                        .isInstanceOf(AccessDeniedException.class);
            } finally {
                SecurityContextHolder.clearContext();
            }
            assertThat(anchors(somebodyElse)).isZero();
        }
    }

    @Nested
    @DisplayName("GET and PATCH /v1/accounts/{id}/identity")
    class IdentityResource {

        @Test
        @DisplayName("staff can read the identity")
        void reads_identity() throws Exception {
            Person person = Person.fresh();
            String id = create(person);

            mvc.perform(get("/v1/accounts/{id}/identity", id).with(supportAgent()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username", is(person.username())))
                    .andExpect(jsonPath("$.email", is(person.email())))
                    .andExpect(jsonPath("$.first_name", is("Jane")))
                    .andExpect(jsonPath("$.email_verified", is(false)))
                    .andExpect(jsonPath("$.enabled", is(true)));
        }

        @Test
        @DisplayName("an end user cannot read it")
        void end_user_cannot_read() throws Exception {
            String id = create(Person.fresh());

            mvc.perform(get("/v1/accounts/{id}/identity", id)
                            .with(jwt().authorities(
                                    new SimpleGrantedAuthority("SCOPE_identity:read"),
                                    new SimpleGrantedAuthority("ROLE_END_USER"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("changes only what the request names, and keeps account_id")
        void updates_identity() throws Exception {
            Person person = Person.fresh();
            String id = create(person);

            mvc.perform(patch("/v1/accounts/{id}/identity", id).with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"first_name": "Janet"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.first_name", is("Janet")))
                    .andExpect(jsonPath("$.last_name", is("Doe")))
                    .andExpect(jsonPath("$.email", is(person.email())));

            var user = keycloak.findByUsername(person.username()).orElseThrow();
            assertThat(user.accountId()).map(UUID::toString).contains(id);
        }
    }

    @Nested
    @DisplayName("DELETE /v1/accounts/{id}")
    class Delete {

        @Test
        @DisplayName("closes the account and erases the identity; repeating it is safe")
        void closes_and_erases() throws Exception {
            Person person = Person.fresh();
            String id = create(person);

            mvc.perform(delete("/v1/accounts/{id}", id).with(superAdmin())).andExpect(status().isNoContent());
            mvc.perform(delete("/v1/accounts/{id}", id).with(superAdmin())).andExpect(status().isNoContent());

            mvc.perform(get("/v1/accounts/{id}", id).with(supportAgent()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("CLOSED")));
            mvc.perform(get("/v1/accounts/{id}/identity", id).with(supportAgent()))
                    .andExpect(status().isNotFound());
            assertThat(keycloak.findByUsername(person.username())).isEmpty();
        }

        @Test
        @DisplayName("is SUPER_ADMIN's alone")
        void delete_needs_super_admin() throws Exception {
            String id = create(Person.fresh());

            mvc.perform(delete("/v1/accounts/{id}", id).with(fraudReviewer()))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("GET /v1/accounts")
    class Search {

        /** End users whose usernames share a prefix nobody else in the realm has. */
        private List<Person> family(String tag, int size) throws Exception {
            List<Person> people = new java.util.ArrayList<>();
            for (int i = 0; i < size; i++) {
                String suffix = tag + "-" + (char) ('a' + i);
                Person person = new Person(suffix, suffix + "@test.vaullet.dev", "acme-" + suffix);
                create(person);
                people.add(person);
            }
            return people;
        }

        @Test
        @DisplayName("finds an end user by exact email address, with the identity alongside the account")
        void by_email() throws Exception {
            Person person = Person.fresh();
            String id = create(person);

            mvc.perform(get("/v1/accounts").param("email", person.email()).with(supportAgent()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()", is(1)))
                    .andExpect(jsonPath("$.data[0].account_id", is(id)))
                    .andExpect(jsonPath("$.data[0].external_ref", is(person.ref())))
                    .andExpect(jsonPath("$.data[0].identity.username", is(person.username())))
                    .andExpect(jsonPath("$.next_cursor").doesNotExist());
        }

        @Test
        @DisplayName("pages through a prefix search with the cursor, and leaves staff out")
        void pages_and_skips_staff() throws Exception {
            String tag = "fam" + UUID.randomUUID().toString().substring(0, 6);
            family(tag, 3);
            // Same prefix, but staff: Keycloak returns them, the search must not.
            keycloak.create(NewKeycloakUser.staff(tag + "-staff", tag + "-staff@test.vaullet.dev", "Ana", "Kovač",
                    "Temp-pass-123", List.of())).orElseThrow();

            String first = mvc.perform(get("/v1/accounts").param("search", tag).param("limit", "2").with(supportAgent()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].identity.username", org.hamcrest.Matchers.contains(tag + "-a", tag + "-b")))
                    .andExpect(jsonPath("$.next_cursor").exists())
                    .andReturn().getResponse().getContentAsString();
            String cursor = JsonPath.read(first, "$.next_cursor");

            mvc.perform(get("/v1/accounts").param("search", tag).param("limit", "2").param("cursor", cursor)
                            .with(supportAgent()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].identity.username", org.hamcrest.Matchers.contains(tag + "-c")))
                    .andExpect(jsonPath("$.next_cursor").doesNotExist());
        }

        @Test
        @DisplayName("refuses free text combined with an exact filter")
        void one_kind_of_filter() throws Exception {
            mvc.perform(get("/v1/accounts").param("search", "jane").param("email", "jane@example.com")
                            .with(supportAgent()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
        }

        @Test
        @DisplayName("refuses a limit out of range and a cursor it did not issue")
        void bad_paging() throws Exception {
            mvc.perform(get("/v1/accounts").param("limit", "0").with(supportAgent()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
            mvc.perform(get("/v1/accounts").param("cursor", "not-a-cursor").with(supportAgent()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
        }

        @Test
        @DisplayName("an end user cannot search")
        void end_user_cannot_search() throws Exception {
            mvc.perform(get("/v1/accounts")
                            .with(jwt().authorities(
                                    new SimpleGrantedAuthority("SCOPE_identity:read"),
                                    new SimpleGrantedAuthority("ROLE_END_USER"))))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("POST /v1/accounts/{id}/password-reset")
    class PasswordReset {

        @Test
        @DisplayName("emails the link again to a user who cannot sign in yet, verified address or not")
        void sends() throws Exception {
            Person person = Person.fresh();
            String id = create(person);

            mvc.perform(post("/v1/accounts/{id}/password-reset", id).with(superAdmin()))
                    .andExpect(status().isNoContent());

            // The invitation, then the reset.
            assertThat(SmtpSink.messagesTo(person.email())).hasSize(2);
        }

        @Test
        @DisplayName("emails a user who can sign in, at their verified address")
        void sends_to_a_verified_address() throws Exception {
            Person person = Person.fresh();
            String id = create(person, true);
            setPassword(person.username());

            mvc.perform(post("/v1/accounts/{id}/password-reset", id).with(superAdmin()))
                    .andExpect(status().isNoContent());

            assertThat(SmtpSink.messagesTo(person.email())).hasSize(2);
        }

        @Test
        @DisplayName("refuses once the address is changed: the link would reach whoever owns the new one")
        void refuses_a_changed_address() throws Exception {
            Person person = Person.fresh();
            String id = create(person, true);
            setPassword(person.username());
            String other = "other-" + person.email();

            mvc.perform(patch("/v1/accounts/{id}/identity", id).with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email": "%s"}""".formatted(other)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email_verified", is(false)));

            mvc.perform(post("/v1/accounts/{id}/password-reset", id).with(superAdmin()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code", is("EMAIL_NOT_VERIFIED")));

            assertThat(SmtpSink.messagesTo(other)).isEmpty();
            assertThat(SmtpSink.messagesTo(person.email())).hasSize(1);
        }

        @Test
        @DisplayName("refuses a closed account")
        void closed_account() throws Exception {
            String id = create(Person.fresh());
            mvc.perform(delete("/v1/accounts/{id}", id).with(superAdmin())).andExpect(status().isNoContent());

            mvc.perform(post("/v1/accounts/{id}/password-reset", id).with(superAdmin()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code", is("ACCOUNT_CLOSED")));
        }

        @Test
        @DisplayName("is SUPER_ADMIN's alone")
        void needs_super_admin() throws Exception {
            String id = create(Person.fresh());

            mvc.perform(post("/v1/accounts/{id}/password-reset", id).with(supportAgent()))
                    .andExpect(status().isForbidden());
        }
    }
}
