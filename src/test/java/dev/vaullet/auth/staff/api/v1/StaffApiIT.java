package dev.vaullet.auth.staff.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vaullet.auth.account.dao.KeycloakUsers;
import dev.vaullet.auth.account.dao.KeycloakUsers.NewKeycloakUser;
import com.jayway.jsonpath.JsonPath;
import dev.vaullet.auth.support.KeycloakTestContainer;
import dev.vaullet.common.test.IntegrationTest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
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
 * Staff management through the whole stack: filter chain, authorisation, validation, the rules and
 * a real Keycloak.
 *
 * <p>The caller is a real {@code SUPER_ADMIN} in the realm, because every write checks the caller in
 * Keycloak as well as in their token. Most tests use one made once for the class; the self-rules are
 * tested by making the caller's subject the staff member being changed, and the live check by
 * presenting a token that claims more than Keycloak now says.
 */
@IntegrationTest
class StaffApiIT {

    /** The usual caller: a staff SUPER_ADMIN in the realm, made on first use and not the target of any test. */
    private static @Nullable UUID admin;

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

    @BeforeEach
    void anAdmin() {
        if (admin == null) {
            String username = unique("root");
            UUID id = keycloak.create(NewKeycloakUser.staff(
                    username, username + "@operator.example", "Root", "Admin", "Temp-pass-123", List.of()))
                    .orElseThrow();
            keycloak.grantRealmRoles(id, List.of("SUPER_ADMIN"));
            admin = id;
        }
    }

    private static JwtRequestPostProcessor superAdmin() {
        UUID caller = admin;
        assertThat(caller).isNotNull();
        return superAdmin(caller);
    }

    private static JwtRequestPostProcessor superAdmin(UUID subject) {
        return jwt().jwt(token -> token.subject(subject.toString())).authorities(
                new SimpleGrantedAuthority("SCOPE_identity:admin"),
                new SimpleGrantedAuthority("SCOPE_identity:read"),
                new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    }

    private static JwtRequestPostProcessor supportAgent() {
        return jwt().authorities(
                new SimpleGrantedAuthority("SCOPE_identity:read"),
                new SimpleGrantedAuthority("ROLE_SUPPORT_AGENT"));
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String body(String username, String... roles) {
        String list = Arrays.stream(roles).map(role -> "\"" + role + "\"").collect(Collectors.joining(", "));
        return """
                {
                  "username": "%s",
                  "email": "%s@operator.example",
                  "first_name": "Ana",
                  "last_name": "Kovač",
                  "roles": [%s],
                  "temporary_password": "Temp-pass-123"
                }""".formatted(username, username, list);
    }

    /** Creates a staff member and returns their id, from the Location header. */
    private UUID create(String username, String... roles) throws Exception {
        String location = mvc.perform(post("/v1/staff").with(superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(username, roles)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    @Nested
    @DisplayName("POST /v1/staff")
    class Create {

        @Test
        @DisplayName("creates a staff member in Keycloak, in the staff group, with their roles and a password")
        void creates_staff() throws Exception {
            String username = unique("ana");

            mvc.perform(post("/v1/staff").with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(username, "SUPPORT_AGENT", "FRAUD_REVIEWER")))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists("Location"))
                    .andExpect(jsonPath("$.username", is(username)))
                    .andExpect(jsonPath("$.email", is(username + "@operator.example")))
                    .andExpect(jsonPath("$.enabled", is(true)))
                    .andExpect(jsonPath("$.roles", containsInAnyOrder("SUPPORT_AGENT", "FRAUD_REVIEWER")))
                    .andExpect(jsonPath("$.temporary_password").doesNotExist());

            var user = keycloak.findByUsername(username).orElseThrow();
            assertThat(keycloak.isStaff(user.id())).isTrue();
            assertThat(keycloak.realmRoles(user.id())).contains("SUPPORT_AGENT", "FRAUD_REVIEWER");
            assertThat(keycloak.canSignIn(user.id())).isTrue();
            // ADR-006's MFA: a new password and an authenticator app before the first token.
            assertThat(keycloak.findById(user.id()).orElseThrow().requiredActions())
                    .contains("UPDATE_PASSWORD", "CONFIGURE_TOTP");
        }

        @Test
        @DisplayName("repeating the request returns the same staff member")
        void repeat_is_a_retry() throws Exception {
            String username = unique("again");

            assertThat(create(username, "FINANCE")).isEqualTo(create(username, "FINANCE"));
        }

        @Test
        @DisplayName("refuses a username an end user has")
        void username_of_an_end_user() throws Exception {
            String username = unique("player");
            keycloak.create(NewKeycloakUser.endUser(
                    username, username + "@operator.example", null, null, false, UUID.randomUUID())).orElseThrow();

            mvc.perform(post("/v1/staff").with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(username, "SUPPORT_AGENT")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code", is("USERNAME_TAKEN")));
        }

        @Test
        @DisplayName("needs at least one role")
        void needs_a_role() throws Exception {
            mvc.perform(post("/v1/staff").with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(unique("norole"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
        }

        @Test
        @DisplayName("END_USER is not a staff role")
        void end_user_is_not_staff() throws Exception {
            mvc.perform(post("/v1/staff").with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(unique("player"), "END_USER")))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("refuses a caller whose token says SUPER_ADMIN when Keycloak no longer does")
        void demoted_caller() throws Exception {
            UUID demoted = create(unique("demoted"), "FINANCE");

            mvc.perform(post("/v1/staff").with(superAdmin(demoted))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(unique("backdoor"), "SUPER_ADMIN")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
        }

        @Test
        @DisplayName("refuses a token whose sub is not a Keycloak user id")
        void token_without_a_user() throws Exception {
            mvc.perform(post("/v1/staff")
                            .with(jwt().jwt(token -> token.subject("not-a-user")).authorities(
                                    new SimpleGrantedAuthority("SCOPE_identity:admin"),
                                    new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(unique("nobody"), "SUPPORT_AGENT")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
        }

        @Test
        @DisplayName("is SUPER_ADMIN's alone")
        void needs_super_admin() throws Exception {
            mvc.perform(post("/v1/staff").with(supportAgent())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(unique("nope"), "SUPPORT_AGENT")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
        }
    }

    @Nested
    @DisplayName("GET /v1/staff")
    class Read {

        @Test
        @DisplayName("lists staff a page at a time")
        void lists() throws Exception {
            create(unique("one"), "SUPPORT_AGENT");
            create(unique("two"), "FINANCE");

            String first = mvc.perform(get("/v1/staff").param("limit", "1").with(superAdmin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()", is(1)))
                    .andExpect(jsonPath("$.next_cursor").exists())
                    .andReturn().getResponse().getContentAsString();
            String cursor = JsonPath.read(first, "$.next_cursor");
            String firstId = JsonPath.read(first, "$.data[0].staff_id");

            String second = mvc.perform(get("/v1/staff").param("limit", "1").param("cursor", cursor).with(superAdmin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()", is(1)))
                    .andReturn().getResponse().getContentAsString();
            assertThat((String) JsonPath.read(second, "$.data[0].staff_id")).isNotEqualTo(firstId);
        }

        @Test
        @DisplayName("fetches one by id")
        void fetches() throws Exception {
            String username = unique("fetched");
            UUID id = create(username, "CONFIG_ADMIN");

            mvc.perform(get("/v1/staff/{id}", id).with(superAdmin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.staff_id", is(id.toString())))
                    .andExpect(jsonPath("$.roles", contains("CONFIG_ADMIN")));
        }

        @Test
        @DisplayName("an end user is not staff, and is not found here")
        void end_user_not_found() throws Exception {
            String username = unique("player");
            UUID endUser = keycloak.create(NewKeycloakUser.endUser(
                    username, username + "@operator.example", null, null, false, UUID.randomUUID())).orElseThrow();

            mvc.perform(get("/v1/staff/{id}", endUser).with(superAdmin()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("support agents cannot list staff")
        void needs_super_admin() throws Exception {
            mvc.perform(get("/v1/staff").with(supportAgent())).andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("PATCH /v1/staff/{id}")
    class Update {

        @Test
        @DisplayName("switches a leaver off")
        void disables() throws Exception {
            UUID id = create(unique("leaver"), "FINANCE");

            mvc.perform(patch("/v1/staff/{id}", id).with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"enabled": false}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled", is(false)))
                    .andExpect(jsonPath("$.roles", contains("FINANCE")));

            assertThat(keycloak.findById(id).orElseThrow().enabled()).isFalse();
        }

        @Test
        @DisplayName("will not change an end user")
        void not_an_end_user() throws Exception {
            String username = unique("player");
            UUID endUser = keycloak.create(NewKeycloakUser.endUser(
                    username, username + "@operator.example", null, null, false, UUID.randomUUID())).orElseThrow();

            mvc.perform(patch("/v1/staff/{id}", endUser).with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"enabled": false}"""))
                    .andExpect(status().isNotFound());
            assertThat(keycloak.findById(endUser).orElseThrow().enabled()).isTrue();
        }

        @Test
        @DisplayName("refuses a blank email address rather than clearing it")
        void blank_email() throws Exception {
            UUID id = create(unique("blank"), "SUPPORT_AGENT");

            mvc.perform(patch("/v1/staff/{id}", id).with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email": ""}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
        }

        @Test
        @DisplayName("needs identity:admin as well as SUPER_ADMIN, and support agents cannot")
        void needs_admin_scope_and_role() throws Exception {
            UUID id = create(unique("guarded"), "SUPPORT_AGENT");
            String disable = """
                    {"enabled": false}""";

            mvc.perform(patch("/v1/staff/{id}", id).with(supportAgent())
                            .contentType(MediaType.APPLICATION_JSON).content(disable))
                    .andExpect(status().isForbidden());
            mvc.perform(patch("/v1/staff/{id}", id)
                            .with(jwt().jwt(token -> token.subject(String.valueOf(admin))).authorities(
                                    new SimpleGrantedAuthority("SCOPE_identity:read"),
                                    new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")))
                            .contentType(MediaType.APPLICATION_JSON).content(disable))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("refuses to let you switch yourself off")
        void not_yourself() throws Exception {
            UUID id = create(unique("self"), "SUPER_ADMIN");

            mvc.perform(patch("/v1/staff/{id}", id).with(superAdmin(id))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"enabled": false}"""))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("SELF_CHANGE_FORBIDDEN")));
        }
    }

    @Nested
    @DisplayName("PUT /v1/staff/{id}/roles")
    class Roles {

        @Test
        @DisplayName("replaces the roles with exactly the set sent")
        void replaces() throws Exception {
            UUID id = create(unique("mover"), "SUPPORT_AGENT");

            mvc.perform(put("/v1/staff/{id}/roles", id).with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"roles": ["FRAUD_REVIEWER", "FINANCE"]}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles", containsInAnyOrder("FRAUD_REVIEWER", "FINANCE")));

            assertThat(keycloak.realmRoles(id)).contains("FRAUD_REVIEWER", "FINANCE").doesNotContain("SUPPORT_AGENT");
        }

        @Test
        @DisplayName("will not give an end user a staff role")
        void not_an_end_user() throws Exception {
            String username = unique("player");
            UUID endUser = keycloak.create(NewKeycloakUser.endUser(
                    username, username + "@operator.example", null, null, false, UUID.randomUUID())).orElseThrow();

            mvc.perform(put("/v1/staff/{id}/roles", endUser).with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"roles": ["SUPER_ADMIN"]}"""))
                    .andExpect(status().isNotFound());
            assertThat(keycloak.realmRoles(endUser)).doesNotContain("SUPER_ADMIN");
        }

        @Test
        @DisplayName("support agents cannot set roles")
        void needs_super_admin() throws Exception {
            UUID id = create(unique("target"), "SUPPORT_AGENT");

            mvc.perform(put("/v1/staff/{id}/roles", id).with(supportAgent())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"roles": ["SUPER_ADMIN"]}"""))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("refuses your own roles")
        void not_your_own() throws Exception {
            UUID id = create(unique("self"), "SUPER_ADMIN");

            mvc.perform(put("/v1/staff/{id}/roles", id).with(superAdmin(id))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"roles": ["SUPER_ADMIN", "FINANCE"]}"""))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("SELF_CHANGE_FORBIDDEN")));
        }

        @Test
        @DisplayName("refuses an empty set: switching somebody off is PATCH enabled=false")
        void not_empty() throws Exception {
            UUID id = create(unique("empty"), "SUPPORT_AGENT");

            mvc.perform(put("/v1/staff/{id}/roles", id).with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"roles": []}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
        }
    }

    @Nested
    @DisplayName("DELETE /v1/staff/{id}")
    class Delete {

        @Test
        @DisplayName("deletes the staff member from Keycloak")
        void deletes() throws Exception {
            UUID id = create(unique("gone"), "SUPPORT_AGENT");

            mvc.perform(delete("/v1/staff/{id}", id).with(superAdmin())).andExpect(status().isNoContent());

            mvc.perform(get("/v1/staff/{id}", id).with(superAdmin())).andExpect(status().isNotFound());
            assertThat(keycloak.findById(id)).isEmpty();
        }

        @Test
        @DisplayName("support agents cannot delete staff")
        void needs_super_admin() throws Exception {
            UUID id = create(unique("kept"), "SUPPORT_AGENT");

            mvc.perform(delete("/v1/staff/{id}", id).with(supportAgent())).andExpect(status().isForbidden());
            assertThat(keycloak.findById(id)).isPresent();
        }

        @Test
        @DisplayName("refuses to delete yourself")
        void not_yourself() throws Exception {
            UUID id = create(unique("self"), "SUPER_ADMIN");

            mvc.perform(delete("/v1/staff/{id}", id).with(superAdmin(id)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("SELF_CHANGE_FORBIDDEN")));
        }

        @Test
        @DisplayName("will not delete an end user")
        void not_an_end_user() throws Exception {
            String username = unique("player");
            UUID endUser = keycloak.create(NewKeycloakUser.endUser(
                    username, username + "@operator.example", null, null, false, UUID.randomUUID())).orElseThrow();

            mvc.perform(delete("/v1/staff/{id}", endUser).with(superAdmin())).andExpect(status().isNotFound());

            assertThat(keycloak.findById(endUser)).isPresent();
        }
    }
}
