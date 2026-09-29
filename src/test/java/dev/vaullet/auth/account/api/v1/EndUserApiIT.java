package dev.vaullet.auth.account.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vaullet.auth.account.dao.KeycloakUsers;
import dev.vaullet.auth.account.dao.KeycloakUsers.NewKeycloakUser;
import dev.vaullet.auth.support.KeycloakTestContainer;
import dev.vaullet.common.test.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End users through the whole stack, in local mode: filter chain, authorisation, validation, the
 * rules, PostgreSQL and a real Keycloak.
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
        return """
                {
                  "external_ref": "%s",
                  "identity": {
                    "username": "%s",
                    "email": "%s",
                    "first_name": "Jane",
                    "last_name": "Doe"
                  }
                }""".formatted(person.ref(), person.username(), person.email());
    }

    /** Creates an end user and returns the account id, from the Location header. */
    private String create(Person person) throws Exception {
        String location = mvc.perform(post("/v1/accounts").with(superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(person)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        return location.substring(location.lastIndexOf('/') + 1);
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
        @DisplayName("repeating the request returns the same account")
        void repeat_is_a_retry() throws Exception {
            Person person = Person.fresh();

            assertThat(create(person)).isEqualTo(create(person));
        }

        @Test
        @DisplayName("finishes a create that stopped after Keycloak, with the account id Keycloak holds")
        void finishes_interrupted_create() throws Exception {
            Person person = Person.fresh();
            UUID earlierAccountId = UUID.randomUUID();
            // What an attempt that died between the two systems leaves behind: the user, no anchor.
            keycloak.create(new NewKeycloakUser(
                    person.username(), person.email(), "Jane", "Doe", false, null, earlierAccountId)).orElseThrow();

            assertThat(create(person)).isEqualTo(earlierAccountId.toString());
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
}
