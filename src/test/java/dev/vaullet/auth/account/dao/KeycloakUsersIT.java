package dev.vaullet.auth.account.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vaullet.auth.account.dao.KeycloakUsers.KeycloakUser;
import dev.vaullet.auth.account.dao.KeycloakUsers.NewKeycloakUser;
import dev.vaullet.auth.account.dao.KeycloakUsers.UpdateOutcome;
import dev.vaullet.auth.common.error.exception.IdentityRejectedException;
import dev.vaullet.auth.config.AuthProperties;
import dev.vaullet.auth.support.KeycloakTestContainer;
import dev.vaullet.auth.support.SmtpSink;
import java.time.Duration;
import java.util.List;
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
 * got stored, a partial update that wiped the rest of the user, a group that was never joined, an
 * email that never left. If a Keycloak upgrade changes one of them, this is the class that should go
 * red.
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
                        Duration.ofSeconds(10)),
                new AuthProperties.UserManagement(Duration.ofHours(72))));
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static NewKeycloakUser newUser(String username, UUID accountId) {
        return NewKeycloakUser.endUser(username, username + "@test.vaullet.dev", "Jane", "Doe", false, accountId);
    }

    private static NewKeycloakUser newStaff(String username) {
        return NewKeycloakUser.staff(username, username + "@test.vaullet.dev", "Ana", "Kovač", "Temp-pass-123",
                List.of(KeycloakUsers.CONFIGURE_TOTP));
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

        var second = NewKeycloakUser.endUser(username, unique("other") + "@test.vaullet.dev", null, null, false,
                UUID.randomUUID());

        assertThat(keycloak.create(second)).isEmpty();
    }

    @Test
    @DisplayName("answers empty when the email address is taken, even under a new username")
    void taken_email_is_empty() {
        String username = unique("first");
        keycloak.create(newUser(username, UUID.randomUUID())).orElseThrow();

        var second = NewKeycloakUser.endUser(unique("second"), username + "@test.vaullet.dev", null, null, false,
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

        UpdateOutcome outcome = keycloak.update(id, null, "Janet", null, null);

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
        var verified = NewKeycloakUser.endUser(username, username + "@test.vaullet.dev", null, null, true,
                UUID.randomUUID());
        UUID id = keycloak.create(verified).orElseThrow();

        keycloak.update(id, unique("moved") + "@test.vaullet.dev", null, null, null);

        assertThat(keycloak.findById(id).orElseThrow().emailVerified()).isFalse();
    }

    @Test
    @DisplayName("an update to an address another user has answers EMAIL_TAKEN")
    void update_to_taken_email() {
        String taken = unique("holder");
        keycloak.create(newUser(taken, UUID.randomUUID())).orElseThrow();
        UUID id = keycloak.create(newUser(unique("mover"), UUID.randomUUID())).orElseThrow();

        assertThat(keycloak.update(id, taken + "@test.vaullet.dev", null, null, null))
                .isEqualTo(UpdateOutcome.EMAIL_TAKEN);
    }

    @Test
    @DisplayName("an update to a user that does not exist answers NOT_FOUND")
    void update_unknown_user() {
        assertThat(keycloak.update(UUID.randomUUID(), null, "Nobody", null, null)).isEqualTo(UpdateOutcome.NOT_FOUND);
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
        var tooShort = NewKeycloakUser.endUser("ab", "ab@test.vaullet.dev", null, null, false, UUID.randomUUID());

        assertThatThrownBy(() -> keycloak.create(tooShort)).isInstanceOf(IdentityRejectedException.class);
    }

    // --- Staff ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("creates staff inside the staff group, with no account_id, and tells them apart from end users")
    void creates_staff_in_the_group() {
        UUID staff = keycloak.create(newStaff(unique("ana"))).orElseThrow();
        UUID endUser = keycloak.create(newUser(unique("jane"), UUID.randomUUID())).orElseThrow();

        // Joined in the same call as the create: Keycloak honours groups there, unlike realmRoles.
        assertThat(keycloak.isStaff(staff)).isTrue();
        assertThat(keycloak.findById(staff).orElseThrow().accountId()).isEmpty();
        assertThat(keycloak.isStaff(endUser)).isFalse();
        assertThat(keycloak.isStaff(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("a new staff member must set a password and enrol an authenticator before their first token")
    void staff_first_sign_in_actions() {
        UUID staff = keycloak.create(newStaff(unique("mfa"))).orElseThrow();

        assertThat(keycloak.findById(staff).orElseThrow().requiredActions())
                .containsExactlyInAnyOrder("UPDATE_PASSWORD", "CONFIGURE_TOTP");
    }

    @Test
    @DisplayName("effective roles include what a composite brings, direct ones only what was granted")
    void effective_roles() {
        UUID staff = keycloak.create(newStaff(unique("effective"))).orElseThrow();
        keycloak.grantRealmRoles(staff, List.of("SUPER_ADMIN"));

        // offline_access arrives through the realm's default composite, never granted directly.
        assertThat(keycloak.effectiveRealmRoles(staff)).contains("SUPER_ADMIN", "offline_access");
        assertThat(keycloak.realmRoles(staff)).contains("SUPER_ADMIN").doesNotContain("offline_access");
        assertThat(keycloak.effectiveRealmRoles(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("lists the staff group's members, a page at a time")
    void lists_staff_members() {
        UUID staff = keycloak.create(newStaff(unique("listed"))).orElseThrow();

        List<UUID> everyone = keycloak.staffMembers(0, 1000).stream().map(KeycloakUser::id).toList();
        assertThat(everyone).contains(staff);
        assertThat(keycloak.staffMembers(0, 1)).hasSize(1);
    }

    @Test
    @DisplayName("grants and revokes several roles at once, and revoking one not held is harmless")
    void grants_and_revokes_roles() {
        UUID staff = keycloak.create(newStaff(unique("roles"))).orElseThrow();

        keycloak.grantRealmRoles(staff, List.of("SUPPORT_AGENT", "FINANCE"));
        keycloak.revokeRealmRoles(staff, List.of("FINANCE", "CONFIG_ADMIN"));

        assertThat(keycloak.realmRoles(staff)).contains("SUPPORT_AGENT").doesNotContain("FINANCE", "CONFIG_ADMIN");
        assertThat(keycloak.realmRoleHolders("SUPPORT_AGENT")).contains(staff);
        assertThat(keycloak.realmRoleHolders("FINANCE")).doesNotContain(staff);
    }

    @Test
    @DisplayName("disables a user without touching the rest, and signs them out")
    void disables_and_signs_out() {
        String username = unique("leaver");
        UUID staff = keycloak.create(newStaff(username)).orElseThrow();

        assertThat(keycloak.update(staff, null, null, null, false)).isEqualTo(UpdateOutcome.UPDATED);

        KeycloakUser user = keycloak.findById(staff).orElseThrow();
        assertThat(user.enabled()).isFalse();
        assertThat(user.email()).isEqualTo(username + "@test.vaullet.dev");
        assertThat(keycloak.logout(staff)).isTrue();
        assertThat(keycloak.logout(UUID.randomUUID())).isFalse();
    }

    // --- Email ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("the test realm has an SMTP server, so email counts as configured")
    void email_is_configured() {
        assertThat(keycloak.emailConfigured()).isTrue();
    }

    @Test
    @DisplayName("sends the set-password email, and it reaches the user with Keycloak's action link")
    void sends_set_password_email() {
        String username = unique("invited");
        UUID id = keycloak.create(newUser(username, UUID.randomUUID())).orElseThrow();

        assertThat(keycloak.sendSetPasswordEmail(id, Duration.ofHours(72))).isTrue();

        // Synchronous in Keycloak: by the time it answered 204, the sink had the message.
        List<SmtpSink.Message> received = SmtpSink.messagesTo(username + "@test.vaullet.dev");
        assertThat(received).hasSize(1);
        assertThat(received.getFirst().link()).hasValueSatisfying(link -> assertThat(link).contains("action-token"));
        assertThat(keycloak.sendSetPasswordEmail(UUID.randomUUID(), Duration.ofHours(1))).isFalse();
    }

    @Test
    @DisplayName("a user created without a password cannot sign in yet; one with a password can")
    void can_sign_in() {
        UUID invited = keycloak.create(newUser(unique("nopass"), UUID.randomUUID())).orElseThrow();
        UUID staff = keycloak.create(newStaff(unique("withpass"))).orElseThrow();

        assertThat(keycloak.canSignIn(invited)).isFalse();
        assertThat(keycloak.canSignIn(staff)).isTrue();
    }

    // --- Search ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("finds users by exact email, by exact username, and by a prefix, carrying their account_id")
    void searches() {
        String username = unique("findme");
        UUID accountId = UUID.randomUUID();
        UUID id = keycloak.create(newUser(username, accountId)).orElseThrow();

        List<KeycloakUser> byEmail = keycloak.search(null, username + "@test.vaullet.dev", null, 0, 10);
        List<KeycloakUser> byUsername = keycloak.search(null, null, username, 0, 10);
        List<KeycloakUser> byPrefix = keycloak.search(username.substring(0, username.length() - 2), null, null, 0, 10);

        assertThat(byEmail).extracting(KeycloakUser::id).containsExactly(id);
        assertThat(byEmail.getFirst().accountId()).contains(accountId);
        assertThat(byUsername).extracting(KeycloakUser::id).containsExactly(id);
        assertThat(byPrefix).extracting(KeycloakUser::id).contains(id);
    }

    @Test
    @DisplayName("an exact email search treats '+' as part of the address, not as a space")
    void plus_in_an_email_address() {
        String username = unique("plus");
        String email = username + "+tag@test.vaullet.dev";
        UUID id = keycloak.create(NewKeycloakUser.endUser(username, email, null, null, false, UUID.randomUUID()))
                .orElseThrow();

        assertThat(keycloak.search(null, email, null, 0, 10)).extracting(KeycloakUser::id).containsExactly(id);
    }
}
