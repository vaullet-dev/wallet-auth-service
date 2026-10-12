package dev.vaullet.auth.staff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.vaullet.auth.account.dao.KeycloakUsers;
import dev.vaullet.auth.account.dao.KeycloakUsers.KeycloakUser;
import dev.vaullet.auth.account.dao.KeycloakUsers.NewKeycloakUser;
import dev.vaullet.auth.account.dao.KeycloakUsers.UpdateOutcome;
import dev.vaullet.auth.common.error.exception.EmailTakenException;
import dev.vaullet.auth.common.error.exception.SelfChangeForbiddenException;
import dev.vaullet.auth.common.error.exception.UsernameTakenException;
import dev.vaullet.auth.common.paging.Slice;
import dev.vaullet.common.error.ResourceNotFoundException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The staff rules, without Keycloak.
 *
 * <p>Keycloak is mocked and the service is real. Asserted here: what a staff member is created as,
 * whether a 409 is this request's own earlier attempt, who may call a write at all, which changes
 * nobody may make to themselves, and the order roles change in. {@code KeycloakUsersIT} covers what
 * Keycloak does with it, and {@code StaffApiIT} the whole path.
 */
@ExtendWith(MockitoExtension.class)
class StaffServiceTest {

    private static final UUID ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID CALLER = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000ff");
    private static final UUID STAFF_GROUP = UUID.fromString("99999999-0000-0000-0000-000000000001");
    private static final String USERNAME = "ana.support";
    private static final String EMAIL = "ana@operator.example";
    private static final String DEFAULT_ROLES = "default-roles-vaullet";

    @Mock
    private KeycloakUsers keycloak;

    @InjectMocks
    private StaffService service;

    /** The caller signs in with a token naming them, as a SUPER_ADMIN's would. */
    @BeforeEach
    void signIn() {
        signInAs(CALLER.toString());
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static void signInAs(@Nullable String subject) {
        Jwt.Builder token = Jwt.withTokenValue("token").header("alg", "none").claim("scope", "identity:admin");
        if (subject != null) {
            token.subject(subject);
        }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token.build()));
    }

    private static NewStaffMember command(StaffRole... roles) {
        return new NewStaffMember(USERNAME, EMAIL, "Ana", "Kovač", Set.of(roles), "Temp-pass-123");
    }

    private static KeycloakUser user(UUID id, String email, boolean enabled) {
        return new KeycloakUser(id, USERNAME, email, "Ana", "Kovač", false, enabled, 0L, Map.of(), List.of());
    }

    /** {@code id} exists, is staff, and directly holds these realm roles. */
    private void staff(UUID id, boolean enabled, String... realmRoles) {
        when(keycloak.findById(id)).thenReturn(Optional.of(user(id, EMAIL, enabled)));
        when(keycloak.isStaff(id)).thenReturn(true);
        when(keycloak.realmRoles(id)).thenReturn(List.of(realmRoles));
    }

    /** What the live check reads about the caller: enabled, staff, and SUPER_ADMIN right now. */
    private void callerIsSuperAdmin() {
        when(keycloak.findById(CALLER)).thenReturn(Optional.of(user(CALLER, "caller@operator.example", true)));
        when(keycloak.isStaff(CALLER)).thenReturn(true);
        when(keycloak.effectiveRealmRoles(CALLER)).thenReturn(List.of(DEFAULT_ROLES, "SUPER_ADMIN"));
    }

    @Nested
    @DisplayName("who may write")
    class Caller {

        @Test
        @DisplayName("a SUPER_ADMIN whose role was taken away is refused, whatever the token still says")
        void demoted_since_the_token() {
            when(keycloak.findById(CALLER)).thenReturn(Optional.of(user(CALLER, "caller@operator.example", true)));
            when(keycloak.isStaff(CALLER)).thenReturn(true);
            when(keycloak.effectiveRealmRoles(CALLER)).thenReturn(List.of(DEFAULT_ROLES, "FINANCE"));

            assertThatThrownBy(() -> service.create(command(StaffRole.SUPER_ADMIN)))
                    .isInstanceOf(AccessDeniedException.class);
            verify(keycloak, never()).create(any());
        }

        @Test
        @DisplayName("a SUPER_ADMIN who has been disabled is refused")
        void disabled_since_the_token() {
            when(keycloak.findById(CALLER)).thenReturn(Optional.of(user(CALLER, "caller@operator.example", false)));

            assertThatThrownBy(() -> service.delete(ID)).isInstanceOf(AccessDeniedException.class);
            verify(keycloak, never()).delete(any());
        }

        @Test
        @DisplayName("a token that does not name its holder is refused, rather than letting the self-rules pass")
        void token_without_a_subject() {
            signInAs(null);

            assertThatThrownBy(() -> service.delete(ID)).isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(keycloak);
        }

        @Test
        @DisplayName("so is one whose subject is not a Keycloak user id")
        void subject_not_a_keycloak_id() {
            signInAs("user");

            assertThatThrownBy(() -> service.replaceRoles(ID, Set.of(StaffRole.FINANCE)))
                    .isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(keycloak);
        }
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("creates the user in the staff group with a temporary password and TOTP enrolment, then grants")
        void creates_then_grants() {
            callerIsSuperAdmin();
            when(keycloak.staffGroupId()).thenReturn(STAFF_GROUP);
            when(keycloak.create(any())).thenReturn(Optional.of(ID));
            staff(ID, true, DEFAULT_ROLES, "SUPPORT_AGENT");

            StaffMember created = service.create(command(StaffRole.SUPPORT_AGENT));

            ArgumentCaptor<NewKeycloakUser> sent = ArgumentCaptor.forClass(NewKeycloakUser.class);
            verify(keycloak).create(sent.capture());
            assertThat(sent.getValue().staff()).isTrue();
            assertThat(sent.getValue().accountId()).isNull();
            assertThat(sent.getValue().temporaryPassword()).isEqualTo("Temp-pass-123");
            assertThat(sent.getValue().requiredActions()).containsExactly(KeycloakUsers.CONFIGURE_TOTP);

            InOrder order = inOrder(keycloak);
            order.verify(keycloak).effectiveRealmRoles(CALLER);
            order.verify(keycloak).staffGroupId();
            order.verify(keycloak).create(any());
            order.verify(keycloak).grantRealmRoles(ID, List.of("SUPPORT_AGENT"));

            // The realm's default composite is not a staff role, and is not reported as one.
            assertThat(created.getRoles()).containsExactly(StaffRole.SUPPORT_AGENT);
        }

        @Test
        @DisplayName("creates nothing when the realm has no staff group")
        void no_staff_group() {
            callerIsSuperAdmin();
            when(keycloak.staffGroupId()).thenThrow(new IllegalStateException("no group"));

            assertThatThrownBy(() -> service.create(command(StaffRole.FINANCE))).isInstanceOf(IllegalStateException.class);
            verify(keycloak, never()).create(any());
        }

        @Test
        @DisplayName("a 409 with the username free means the email address is taken")
        void email_taken() {
            callerIsSuperAdmin();
            when(keycloak.staffGroupId()).thenReturn(STAFF_GROUP);
            when(keycloak.create(any())).thenReturn(Optional.empty());
            when(keycloak.findByUsername(USERNAME)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(command(StaffRole.FINANCE))).isInstanceOf(EmailTakenException.class);
        }

        @Test
        @DisplayName("refuses a username that belongs to an end user, even with the same email address")
        void username_of_an_end_user() {
            callerIsSuperAdmin();
            when(keycloak.staffGroupId()).thenReturn(STAFF_GROUP);
            when(keycloak.create(any())).thenReturn(Optional.empty());
            when(keycloak.findByUsername(USERNAME)).thenReturn(Optional.of(user(ID, EMAIL, true)));
            when(keycloak.isStaff(ID)).thenReturn(false);

            assertThatThrownBy(() -> service.create(command(StaffRole.FINANCE))).isInstanceOf(UsernameTakenException.class);
            verify(keycloak, never()).grantRealmRoles(any(), any());
        }

        @Test
        @DisplayName("refuses a username whose owner has another email address")
        void username_with_another_email() {
            callerIsSuperAdmin();
            when(keycloak.staffGroupId()).thenReturn(STAFF_GROUP);
            when(keycloak.create(any())).thenReturn(Optional.empty());
            when(keycloak.findByUsername(USERNAME)).thenReturn(Optional.of(user(ID, "someone@operator.example", true)));

            assertThatThrownBy(() -> service.create(command(StaffRole.FINANCE))).isInstanceOf(UsernameTakenException.class);
        }

        @Test
        @DisplayName("finishes an earlier attempt that stopped before granting its roles")
        void finishes_interrupted_create() {
            callerIsSuperAdmin();
            when(keycloak.staffGroupId()).thenReturn(STAFF_GROUP);
            when(keycloak.create(any())).thenReturn(Optional.empty());
            when(keycloak.findByUsername(USERNAME))
                    .thenReturn(Optional.of(user(ID, EMAIL.toUpperCase(Locale.ROOT), true)));
            when(keycloak.isStaff(ID)).thenReturn(true);
            when(keycloak.findById(ID)).thenReturn(Optional.of(user(ID, EMAIL, true)));
            when(keycloak.realmRoles(ID)).thenReturn(List.of(DEFAULT_ROLES), List.of(DEFAULT_ROLES, "FINANCE"));

            StaffMember finished = service.create(command(StaffRole.FINANCE));

            verify(keycloak).grantRealmRoles(ID, List.of("FINANCE"));
            assertThat(finished.getRoles()).containsExactly(StaffRole.FINANCE);
        }

        @Test
        @DisplayName("finishing is a role change, so it cannot hand the caller roles of their own")
        void finishing_cannot_regrant_the_caller() {
            callerIsSuperAdmin();
            when(keycloak.staffGroupId()).thenReturn(STAFF_GROUP);
            when(keycloak.create(any())).thenReturn(Optional.empty());
            // The caller's own user: staff, same email, roles taken away.
            when(keycloak.findByUsername(USERNAME)).thenReturn(Optional.of(user(CALLER, EMAIL, true)));
            when(keycloak.realmRoles(CALLER)).thenReturn(List.of(DEFAULT_ROLES));

            assertThatThrownBy(() -> service.create(command(StaffRole.SUPER_ADMIN, StaffRole.FINANCE)))
                    .isInstanceOf(SelfChangeForbiddenException.class);
            verify(keycloak, never()).grantRealmRoles(any(), any());
        }

        @Test
        @DisplayName("a repeat of a finished create returns the staff member and changes nothing")
        void replays_finished_create() {
            callerIsSuperAdmin();
            when(keycloak.staffGroupId()).thenReturn(STAFF_GROUP);
            when(keycloak.create(any())).thenReturn(Optional.empty());
            when(keycloak.findByUsername(USERNAME)).thenReturn(Optional.of(user(ID, EMAIL, true)));
            when(keycloak.isStaff(ID)).thenReturn(true);
            when(keycloak.realmRoles(ID)).thenReturn(List.of(DEFAULT_ROLES, "FINANCE"));

            StaffMember replayed = service.create(command(StaffRole.FINANCE));

            assertThat(replayed.getId()).isEqualTo(ID);
            verify(keycloak, never()).grantRealmRoles(any(), any());
        }

        @Test
        @DisplayName("refuses when the same person already holds different roles")
        void same_person_other_roles() {
            callerIsSuperAdmin();
            when(keycloak.staffGroupId()).thenReturn(STAFF_GROUP);
            when(keycloak.create(any())).thenReturn(Optional.empty());
            when(keycloak.findByUsername(USERNAME)).thenReturn(Optional.of(user(ID, EMAIL, true)));
            when(keycloak.isStaff(ID)).thenReturn(true);
            when(keycloak.realmRoles(ID)).thenReturn(List.of("SUPPORT_AGENT"));

            assertThatThrownBy(() -> service.create(command(StaffRole.FINANCE))).isInstanceOf(UsernameTakenException.class);
            verify(keycloak, never()).grantRealmRoles(any(), any());
        }
    }

    @Nested
    @DisplayName("list")
    class ListStaff {

        private final UUID second = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
        private final UUID third = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");

        @Test
        @DisplayName("asks for one more than the limit, so it knows a next page exists")
        void next_page() {
            when(keycloak.staffMembers(10, 3)).thenReturn(List.of(
                    user(ID, EMAIL, true), user(second, "b@operator.example", true), user(third, "c@operator.example", true)));
            when(keycloak.realmRoleHolders(anyString())).thenReturn(Set.of());
            when(keycloak.realmRoleHolders("SUPPORT_AGENT")).thenReturn(Set.of(ID, third));
            when(keycloak.realmRoleHolders("SUPER_ADMIN")).thenReturn(Set.of(second));

            Slice<StaffMember> page = service.list(10, 2);

            assertThat(page.getItems()).extracting(StaffMember::getId).containsExactly(ID, second);
            assertThat(page.getItems().get(0).getRoles()).containsExactly(StaffRole.SUPPORT_AGENT);
            assertThat(page.getItems().get(1).getRoles()).containsExactly(StaffRole.SUPER_ADMIN);
            assertThat(page.getNext()).contains(12);
        }

        @Test
        @DisplayName("a short page is the last")
        void last_page() {
            when(keycloak.staffMembers(0, 51)).thenReturn(List.of(user(ID, EMAIL, true)));
            when(keycloak.realmRoleHolders(anyString())).thenReturn(Set.of());

            Slice<StaffMember> page = service.list(0, 50);

            assertThat(page.getItems()).hasSize(1);
            assertThat(page.getNext()).isEmpty();
        }
    }

    @Nested
    @DisplayName("find")
    class Find {

        @Test
        @DisplayName("a user outside the staff group is not found, whoever they are")
        void not_staff() {
            when(keycloak.findById(ID)).thenReturn(Optional.of(user(ID, EMAIL, true)));
            when(keycloak.isStaff(ID)).thenReturn(false);

            assertThatThrownBy(() -> service.find(ID)).isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("refuses to let you disable yourself, before calling Keycloak")
        void disable_yourself() {
            assertThatThrownBy(() -> service.update(CALLER, new StaffChanges(null, null, null, false)))
                    .isInstanceOf(SelfChangeForbiddenException.class);
            verifyNoInteractions(keycloak);
        }

        @Test
        @DisplayName("you may change your own name")
        void rename_yourself() {
            when(keycloak.findById(CALLER)).thenReturn(Optional.of(user(CALLER, EMAIL, true)));
            when(keycloak.isStaff(CALLER)).thenReturn(true);
            when(keycloak.effectiveRealmRoles(CALLER)).thenReturn(List.of("SUPER_ADMIN"));
            when(keycloak.realmRoles(CALLER)).thenReturn(List.of("SUPER_ADMIN"));
            when(keycloak.update(CALLER, null, "Anna", null, null)).thenReturn(UpdateOutcome.UPDATED);

            service.update(CALLER, new StaffChanges(null, "Anna", null, null));

            verify(keycloak).update(CALLER, null, "Anna", null, null);
        }

        @Test
        @DisplayName("disabling somebody else also signs them out")
        void disables_and_signs_out() {
            callerIsSuperAdmin();
            staff(ID, true, "FINANCE");
            when(keycloak.update(ID, null, null, null, false)).thenReturn(UpdateOutcome.UPDATED);

            service.update(ID, new StaffChanges(null, null, null, false));

            InOrder order = inOrder(keycloak);
            order.verify(keycloak).update(ID, null, null, null, false);
            order.verify(keycloak).logout(ID);
        }

        @Test
        @DisplayName("a repeated disable still signs them out, though the switch is already off")
        void repeated_disable_signs_out() {
            callerIsSuperAdmin();
            staff(ID, false, "FINANCE");

            service.update(ID, new StaffChanges(null, null, null, false));

            verify(keycloak, never()).update(any(), any(), any(), any(), any());
            verify(keycloak).logout(ID);
        }

        @Test
        @DisplayName("sends nothing when nothing changes: same address in another case, same switch")
        void nothing_to_change() {
            callerIsSuperAdmin();
            staff(ID, true, "FINANCE");

            service.update(ID, new StaffChanges(EMAIL.toUpperCase(Locale.ROOT), null, null, true));

            verify(keycloak, never()).update(any(), any(), any(), any(), any());
            verify(keycloak, never()).logout(any());
        }

        @Test
        @DisplayName("an address another user has is EMAIL_TAKEN")
        void email_taken() {
            callerIsSuperAdmin();
            when(keycloak.findById(ID)).thenReturn(Optional.of(user(ID, EMAIL, true)));
            when(keycloak.isStaff(ID)).thenReturn(true);
            when(keycloak.update(ID, "taken@operator.example", null, null, null)).thenReturn(UpdateOutcome.EMAIL_TAKEN);

            assertThatThrownBy(() -> service.update(ID, new StaffChanges("taken@operator.example", null, null, null)))
                    .isInstanceOf(EmailTakenException.class);
        }
    }

    @Nested
    @DisplayName("replaceRoles")
    class ReplaceRoles {

        @Test
        @DisplayName("refuses your own roles, before calling Keycloak")
        void own_roles() {
            assertThatThrownBy(() -> service.replaceRoles(CALLER, Set.of(StaffRole.FINANCE)))
                    .isInstanceOf(SelfChangeForbiddenException.class);
            verifyNoInteractions(keycloak);
        }

        @Test
        @DisplayName("revokes before granting, and only the difference")
        void revokes_then_grants() {
            callerIsSuperAdmin();
            staff(ID, true, DEFAULT_ROLES, "SUPPORT_AGENT", "FINANCE");

            service.replaceRoles(ID, Set.of(StaffRole.SUPPORT_AGENT, StaffRole.CONFIG_ADMIN));

            InOrder order = inOrder(keycloak);
            order.verify(keycloak).revokeRealmRoles(ID, List.of("FINANCE"));
            order.verify(keycloak).grantRealmRoles(ID, List.of("CONFIG_ADMIN"));
        }

        @Test
        @DisplayName("touches nobody outside the staff group")
        void not_staff() {
            callerIsSuperAdmin();
            when(keycloak.findById(ID)).thenReturn(Optional.of(user(ID, EMAIL, true)));
            when(keycloak.isStaff(ID)).thenReturn(false);

            assertThatThrownBy(() -> service.replaceRoles(ID, Set.of(StaffRole.SUPER_ADMIN)))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(keycloak, never()).grantRealmRoles(any(), any());
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("refuses to delete yourself, before calling Keycloak")
        void yourself() {
            assertThatThrownBy(() -> service.delete(CALLER)).isInstanceOf(SelfChangeForbiddenException.class);
            verifyNoInteractions(keycloak);
        }

        @Test
        @DisplayName("deletes nobody outside the staff group — an end user is not found here")
        void not_staff() {
            callerIsSuperAdmin();
            when(keycloak.findById(ID)).thenReturn(Optional.of(user(ID, EMAIL, true)));
            when(keycloak.isStaff(ID)).thenReturn(false);

            assertThatThrownBy(() -> service.delete(ID)).isInstanceOf(ResourceNotFoundException.class);
            verify(keycloak, never()).delete(any());
        }

        @Test
        @DisplayName("deletes a staff member")
        void deletes() {
            callerIsSuperAdmin();
            when(keycloak.findById(ID)).thenReturn(Optional.of(user(ID, EMAIL, true)));
            when(keycloak.isStaff(ID)).thenReturn(true);
            when(keycloak.delete(ID)).thenReturn(true);

            service.delete(ID);

            verify(keycloak).delete(ID);
        }
    }
}
