package dev.vaullet.auth.account.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vaullet.auth.account.dao.KeycloakUsers.KeycloakUser;
import dev.vaullet.auth.account.dao.KeycloakUsers.NewKeycloakUser;
import dev.vaullet.auth.account.dao.KeycloakUsers.UpdateOutcome;
import dev.vaullet.auth.common.error.exception.IdentityRejectedException;
import dev.vaullet.auth.config.AuthProperties;
import dev.vaullet.auth.support.KeycloakTestContainer;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The adapter against a real Keycloak, and nothing else — no Spring context, no database.
 *
 * <p>Each test pins down one Keycloak behaviour the adapter depends on. They are the assumptions
 * that fail silently in production when they are wrong: a disabled user, an attribute that never
 * got stored, a partial update that wiped the rest of the user. If a Keycloak upgrade changes one of
 * them, this is the class that should go red.
 */
class KeycloakUsersIT {

    private static KeycloakUsers keycloak;

    @BeforeAll
    static void connect() {
        keycloak = new KeycloakUsers(new AuthProperties(
                AuthProperties.Provider.LOCAL,
                new AuthProperties.Keycloak(
                        KeycloakTestContainer.url(),
                        KeycloakTestContainer.REALM,
                        KeycloakTestContainer.CLIENT_ID,
                        KeycloakTestContainer.CLIENT_SECRET,
                        Duration.ofSeconds(10))));
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static NewKeycloakUser newUser(String username, UUID accountId) {
        return new NewKeycloakUser(username, username + "@test.vaullet.dev", "Jane", "Doe", false, null, accountId);
    }

    @Test
    @DisplayName("the test realm declares account_id as admin-only, so the guard lets creation through")
    void guard_passes() {
        keycloak.requireAccountIdEditableByAdminsOnly();
    }

    @Test
    @DisplayName("creates an enabled user that carries its account_id")
    void creates_enabled_user_with_account_id() {
        String username = unique("jane");
        UUID accountId = UUID.randomUUID();

        UUID id = keycloak.create(newUser(username, accountId)).orElseThrow();

        KeycloakUser user = keycloak.findById(id).orElseThrow();
        assertThat(user.enabled()).isTrue();
        assertThat(user.username()).isEqualTo(username);
        assertThat(user.email()).isEqualTo(username + "@test.vaullet.dev");
        assertThat(user.emailVerified()).isFalse();
        // Keycloak drops an undeclared attribute without a word; this is the line that notices.
        assertThat(user.accountId()).contains(accountId);
    }

    @Test
    @DisplayName("answers empty, not an exception, when the username is taken")
    void taken_username_is_empty() {
        String username = unique("taken");
        keycloak.create(newUser(username, UUID.randomUUID())).orElseThrow();

        var second = new NewKeycloakUser(username, unique("other") + "@test.vaullet.dev", null, null, false, null,
                UUID.randomUUID());

        assertThat(keycloak.create(second)).isEmpty();
    }

    @Test
    @DisplayName("answers empty when the email address is taken, even under a new username")
    void taken_email_is_empty() {
        String username = unique("first");
        keycloak.create(newUser(username, UUID.randomUUID())).orElseThrow();

        var second = new NewKeycloakUser(unique("second"), username + "@test.vaullet.dev", null, null, false, null,
                UUID.randomUUID());

        assertThat(keycloak.create(second)).isEmpty();
    }

    @Test
    @DisplayName("finds a user by username regardless of case, since Keycloak stores it lower-cased")
    void finds_by_username() {
        String username = unique("case");
        UUID id = keycloak.create(newUser(username, UUID.randomUUID())).orElseThrow();

        assertThat(keycloak.findByUsername(username.toUpperCase(Locale.ROOT))).map(KeycloakUser::id).contains(id);
        assertThat(keycloak.findByUsername(unique("nobody"))).isEmpty();
    }

    @Test
    @DisplayName("grants a realm role, and granting it again is harmless")
    void grants_role_idempotently() {
        UUID id = keycloak.create(newUser(unique("role"), UUID.randomUUID())).orElseThrow();

        keycloak.grantRealmRole(id, "END_USER");
        keycloak.grantRealmRole(id, "END_USER");

        assertThat(keycloak.realmRoles(id)).contains("END_USER");
    }

    @Test
    @DisplayName("a partial update changes only what it names, and keeps account_id")
    void partial_update_keeps_the_rest() {
        String username = unique("update");
        UUID accountId = UUID.randomUUID();
        UUID id = keycloak.create(newUser(username, accountId)).orElseThrow();

        UpdateOutcome outcome = keycloak.update(id, null, "Janet", null);

        assertThat(outcome).isEqualTo(UpdateOutcome.UPDATED);
        KeycloakUser user = keycloak.findById(id).orElseThrow();
        assertThat(user.firstName()).isEqualTo("Janet");
        assertThat(user.lastName()).isEqualTo("Doe");
        assertThat(user.email()).isEqualTo(username + "@test.vaullet.dev");
        assertThat(user.accountId()).contains(accountId);
    }

    @Test
    @DisplayName("a new email address is marked unverified")
    void new_email_is_unverified() {
        String username = unique("email");
        var verified = new NewKeycloakUser(username, username + "@test.vaullet.dev", null, null, true, null,
                UUID.randomUUID());
        UUID id = keycloak.create(verified).orElseThrow();

        keycloak.update(id, unique("moved") + "@test.vaullet.dev", null, null);

        assertThat(keycloak.findById(id).orElseThrow().emailVerified()).isFalse();
    }

    @Test
    @DisplayName("an update to an address another user has answers EMAIL_TAKEN")
    void update_to_taken_email() {
        String taken = unique("holder");
        keycloak.create(newUser(taken, UUID.randomUUID())).orElseThrow();
        UUID id = keycloak.create(newUser(unique("mover"), UUID.randomUUID())).orElseThrow();

        assertThat(keycloak.update(id, taken + "@test.vaullet.dev", null, null)).isEqualTo(UpdateOutcome.EMAIL_TAKEN);
    }

    @Test
    @DisplayName("an update to a user that does not exist answers NOT_FOUND")
    void update_unknown_user() {
        assertThat(keycloak.update(UUID.randomUUID(), null, "Nobody", null)).isEqualTo(UpdateOutcome.NOT_FOUND);
    }

    @Test
    @DisplayName("deletes a user, and a second delete reports there was nothing to delete")
    void deletes_idempotently() {
        UUID id = keycloak.create(newUser(unique("gone"), UUID.randomUUID())).orElseThrow();

        assertThat(keycloak.delete(id)).isTrue();
        assertThat(keycloak.delete(id)).isFalse();
        assertThat(keycloak.findById(id)).isEmpty();
    }

    @Test
    @DisplayName("a value the realm's user profile rejects comes back as IdentityRejectedException")
    void rejected_values() {
        // Keycloak's default user profile wants at least three characters in a username. The API
        // checks that too, so this is the only place Keycloak's own refusal is seen.
        var tooShort = new NewKeycloakUser("ab", "ab@test.vaullet.dev", null, null, false, null, UUID.randomUUID());

        assertThatThrownBy(() -> keycloak.create(tooShort)).isInstanceOf(IdentityRejectedException.class);
    }
}
