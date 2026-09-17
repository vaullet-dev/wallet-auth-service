package dev.vaullet.auth.account.api.v1;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vaullet.common.test.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole stack: filter chain, authorisation, binding, validation, rules, PostgreSQL, error bodies.
 *
 * <p>The security assertions here are the ones no other test can make. The {@code local} profile
 * grants the anonymous principal every scope and both roles at once, so a developer can exercise
 * every endpoint by hand — which means the <em>separation</em> between {@code SUPER_ADMIN} and
 * {@code FRAUD_REVIEWER} is invisible locally. ADR-006 treats that separation as structural, so it
 * has to be proved somewhere, with distinct principals. This is the somewhere.
 */
@IntegrationTest
class AccountApiIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    /** The two roles ADR-006 keeps apart, plus a read-only principal. */
    private static JwtRequestPostProcessor superAdmin() {
        return jwt().authorities(
                new SimpleGrantedAuthority("SCOPE_identity:admin"),
                new SimpleGrantedAuthority("SCOPE_identity:read"),
                new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    }

    private static JwtRequestPostProcessor fraudReviewer() {
        return jwt().authorities(
                new SimpleGrantedAuthority("SCOPE_identity:admin"),
                new SimpleGrantedAuthority("SCOPE_identity:read"),
                new SimpleGrantedAuthority("ROLE_FRAUD_REVIEWER"));
    }

    private static JwtRequestPostProcessor reader() {
        return jwt().authorities(
                new SimpleGrantedAuthority("SCOPE_identity:read"),
                new SimpleGrantedAuthority("ROLE_SUPPORT_AGENT"));
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE identity_schema.accounts");
    }

    /**
     * Creates an account and returns its id, taken from the {@code Location} header rather than from
     * the body — so every call also asserts that the header points somewhere, which is the half of
     * the 201 contract a body assertion would miss.
     */
    private String createAccount(String externalRef) throws Exception {
        String location = mvc.perform(post("/v1/accounts").with(superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"external_ref\": \"%s\"}".formatted(externalRef)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        return location.substring(location.lastIndexOf('/') + 1);
    }

    @Nested
    @DisplayName("POST /v1/accounts")
    class Create {

        @Test
        @DisplayName("creates an account and points at it with Location")
        void creates() throws Exception {
            mvc.perform(post("/v1/accounts").with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"external_ref": "acme-user-8813"}"""))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", notNullValue()))
                    // snake_case on the wire (ADR-011 §8), set once by the platform default.
                    .andExpect(jsonPath("$.account_id").exists())
                    .andExpect(jsonPath("$.external_ref", is("acme-user-8813")))
                    .andExpect(jsonPath("$.status", is("ACTIVE")))
                    // An account is unlinked until its user first authenticates.
                    .andExpect(jsonPath("$.linked", is(false)))
                    // ADR-006: downstream keys on account_id, never on the Keycloak subject, so the
                    // subject is not in the representation at all.
                    .andExpect(jsonPath("$.keycloak_sub").doesNotExist());
        }

        @Test
        @DisplayName("repeating the request returns the original account, not a second one")
        void repeat_is_a_retry() throws Exception {
            String first = createAccount("acme-user-8813");
            String second = createAccount("acme-user-8813");

            assertSameAccount(first, second);
            // No Idempotency-Key was sent on either call. The natural key carried it.
        }

        @Test
        @DisplayName("rejects a request with no external_ref, naming the field")
        void requires_an_external_ref() throws Exception {
            mvc.perform(post("/v1/accounts").with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
                    .andExpect(jsonPath("$.errors[0].field", is("externalRef")));
        }

        @Test
        @DisplayName("refuses a caller who is not a SUPER_ADMIN, even with the right scope")
        void creation_needs_super_admin() throws Exception {
            mvc.perform(post("/v1/accounts").with(fraudReviewer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"external_ref": "acme-user-8813"}"""))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
        }

        private void assertSameAccount(String first, String second) {
            assertThat(second).isEqualTo(first);
            Integer rows = jdbc.queryForObject("SELECT count(*) FROM identity_schema.accounts", Integer.class);
            assertThat(rows).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("GET /v1/accounts")
    class Read {

        @Test
        @DisplayName("fetches by id and by the operator's own reference")
        void fetches() throws Exception {
            String id = createAccount("acme-user-8813");

            mvc.perform(get("/v1/accounts/{id}", id).with(reader()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.account_id", is(id)));

            mvc.perform(get("/v1/accounts/by-ref/{ref}", "acme-user-8813").with(reader()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.account_id", is(id)));
        }

        @Test
        @DisplayName("404s for an unknown account, in problem+json")
        void unknown_account() throws Exception {
            mvc.perform(get("/v1/accounts/{id}", UUID.randomUUID()).with(reader()))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.code", is("RESOURCE_NOT_FOUND")));
        }

        @Test
        @DisplayName("refuses an unauthenticated request")
        void needs_a_token() throws Exception {
            mvc.perform(get("/v1/accounts/{id}", UUID.randomUUID()))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("PATCH /v1/accounts/{id}")
    class ChangeStatus {

        @Test
        @DisplayName("a FRAUD_REVIEWER can freeze and unfreeze")
        void freeze_and_unfreeze() throws Exception {
            String id = createAccount("acme-user-8813");

            mvc.perform(patch("/v1/accounts/{id}", id).with(fraudReviewer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"status": "SUSPENDED"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("SUSPENDED")));

            mvc.perform(patch("/v1/accounts/{id}", id).with(fraudReviewer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"status": "ACTIVE"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("ACTIVE")));
        }

        @Test
        @DisplayName("a SUPER_ADMIN cannot freeze — separation of duties, structurally")
        void freezing_needs_fraud_reviewer() throws Exception {
            String id = createAccount("acme-user-8813");

            // The one assertion the local profile cannot make, because it grants both roles at once.
            mvc.perform(patch("/v1/accounts/{id}", id).with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"status": "SUSPENDED"}"""))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a closed account cannot be reopened, and says why")
        void closed_is_terminal() throws Exception {
            String id = createAccount("acme-user-8813");
            mvc.perform(patch("/v1/accounts/{id}", id).with(fraudReviewer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"status": "CLOSED"}"""))
                    .andExpect(status().isOk());

            mvc.perform(patch("/v1/accounts/{id}", id).with(fraudReviewer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"status": "ACTIVE"}"""))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code", is("ACCOUNT_CLOSED")));
        }

        @Test
        @DisplayName("closing twice succeeds, so a retried close is safe")
        void closing_twice() throws Exception {
            String id = createAccount("acme-user-8813");
            for (int attempt = 0; attempt < 2; attempt++) {
                mvc.perform(patch("/v1/accounts/{id}", id).with(fraudReviewer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"status": "CLOSED"}"""))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status", is("CLOSED")));
            }
        }

        @Test
        @DisplayName("rejects an unknown status value at deserialisation")
        void unknown_status() throws Exception {
            String id = createAccount("acme-user-8813");

            // LOCKED is ADR-006's old spelling. Binding to the enum means it never reaches a rule.
            mvc.perform(patch("/v1/accounts/{id}", id).with(fraudReviewer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"status": "LOCKED"}"""))
                    .andExpect(status().isBadRequest());
        }
    }
}
