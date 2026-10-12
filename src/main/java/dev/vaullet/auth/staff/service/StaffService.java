package dev.vaullet.auth.staff.service;

import dev.vaullet.auth.account.dao.KeycloakUsers;
import dev.vaullet.auth.account.dao.KeycloakUsers.KeycloakUser;
import dev.vaullet.auth.account.dao.KeycloakUsers.NewKeycloakUser;
import dev.vaullet.auth.common.error.exception.EmailTakenException;
import dev.vaullet.auth.common.error.exception.SelfChangeForbiddenException;
import dev.vaullet.auth.common.error.exception.UsernameTakenException;
import dev.vaullet.auth.common.paging.Slice;
import dev.vaullet.common.error.ResourceNotFoundException;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/**
 * The operator's staff, in Keycloak's realm: created with a temporary password, listed, changed,
 * given roles and deleted. What the admin dashboard's staff pages call.
 *
 * <p><b>Who is staff is a group, not a role.</b> Every staff member is created in the realm's
 * {@code staff} group, in the same Keycloak call that creates the user. Roles cannot be given in that
 * call, so staff marked only by their roles would make a create that stopped half-way produce
 * somebody invisible: holding the username, unreachable through this API. Membership makes them
 * visible from the first write. And every operation here refuses a user outside the group, so an end
 * user cannot be reached through {@code /v1/staff} — just as staff, who have no account, cannot be
 * reached through {@code /v1/accounts}.
 *
 * <p><b>First sign-in.</b> The password the admin sets is temporary, and the new member must also
 * enrol an authenticator app; Keycloak runs both before it issues them a token. ADR-006 makes MFA
 * mandatory for every role but {@code END_USER}. Enrolment is what this service can require. That
 * every later sign-in asks for the code is the realm's browser flow, whose default asks anyone with
 * an authenticator configured.
 *
 * <p><b>The caller is checked in Keycloak, not only in their token.</b> {@code @PreAuthorize} reads
 * the roles a token carried when it was issued, up to a token lifetime ago. For the writes here —
 * which make {@code SUPER_ADMIN}s and take roles away — that is not enough: a {@code SUPER_ADMIN}
 * demoted, disabled or deleted a minute ago would still pass, and could use the minutes left to
 * create themselves a way back in. So every write also asks Keycloak whether the caller is, now, an
 * enabled member of the staff group holding {@code SUPER_ADMIN}. Reads keep to the token. Under the
 * local profile there is no realm user behind a request, and the check does not apply.
 *
 * <p><b>Nobody administers themselves.</b> Changing your own roles, disabling yourself and deleting
 * yourself are refused ({@code SELF_CHANGE_FORBIDDEN}), and so is a token that does not say who its
 * holder is — without a {@code sub} the rule could not tell — and any caller who is not a Keycloak
 * token at all, the local profile's anonymous principal aside. With the live check, whoever removes a
 * {@code SUPER_ADMIN} is another one who still is, so the last cannot be removed; only two of them
 * removing each other at the same moment could do it.
 *
 * <p>Every write is logged with the caller's id, for the audit trail ADR-006 asks of role changes
 * until the Audit service exists to receive it.
 *
 * <p>Keycloak is the only system involved — no anchor, no transaction. Only registered with
 * {@code auth.provider: local}.
 */
@Validated
@Service
@ConditionalOnProperty(name = "auth.provider", havingValue = "local", matchIfMissing = true)
public class StaffService {

    /** Done at first sign-in, before any token: enrol an authenticator app (ADR-006). */
    static final List<String> FIRST_SIGN_IN_ACTIONS = List.of(KeycloakUsers.CONFIGURE_TOTP);

    private static final Logger log = LoggerFactory.getLogger(StaffService.class);

    private final KeycloakUsers keycloak;

    StaffService(KeycloakUsers keycloak) {
        this.keycloak = keycloak;
    }

    /**
     * Create a staff member, or return the one this request already created.
     *
     * <p>Two calls to Keycloak: the user, in the staff group and with its temporary password, then
     * its roles. A 409 from the first is not an error yet. The username belongs to this request's own
     * earlier attempt if that user is staff and has the same email address — and then it either holds
     * no staff role, because the attempt stopped before granting them, or exactly the ones asked for,
     * because it finished and only the response was lost. The first is finished and the second
     * returned, both with 201. Anything else is somebody else's username.
     *
     * <p>A repeat does not touch the password: it stays whatever the first attempt set.
     */
    @PreAuthorize(StaffAccess.ADMIN)
    public StaffMember create(NewStaffMember command) {
        Optional<UUID> caller = caller();
        requireCurrentSuperAdmin(caller);
        // Looked up before the create on purpose: Keycloak fails a create naming a group it does not
        // have with a bare 500, and this says what is actually wrong.
        keycloak.staffGroupId();

        Optional<UUID> created = keycloak.create(NewKeycloakUser.staff(
                command.getUsername(),
                command.getEmail(),
                command.getFirstName(),
                command.getLastName(),
                command.getTemporaryPassword(),
                FIRST_SIGN_IN_ACTIONS));

        UUID id;
        if (created.isPresent()) {
            id = created.get();
        } else {
            KeycloakUser existing = earlierAttempt(command);
            Set<StaffRole> held = staffRoles(keycloak.realmRoles(existing.id()));
            if (held.equals(command.getRoles())) {
                return toMember(existing, held);
            }
            if (!held.isEmpty()) {
                throw new UsernameTakenException(command.getUsername());
            }
            id = existing.id();
            // Finishing grants roles, so it is a role change like any other — and a caller whose own
            // roles were taken away must not be able to give themselves new ones by "re-creating".
            refuseSelf(caller, id, "change your own roles");
            log.info("Finishing an interrupted create: staff member {} has no roles yet", id);
        }
        keycloak.grantRealmRoles(id, names(command.getRoles()));
        log.info("Staff member {} created with roles {} by {}", id, command.getRoles(), actor(caller));
        return load(id);
    }

    /**
     * One page of staff, by username.
     *
     * <p>Roles come from one call per staff role rather than one per member — five calls for a page
     * of any size, which stays cheap because staff are dozens, not millions (ADR-006).
     */
    @PreAuthorize(StaffAccess.READ)
    public Slice<StaffMember> list(int position, int limit) {
        // One more than asked for: whether a next page exists is known without a call that returns
        // nothing.
        List<KeycloakUser> members = keycloak.staffMembers(position, limit + 1);
        boolean more = members.size() > limit;
        List<KeycloakUser> page = more ? members.subList(0, limit) : members;
        Map<UUID, Set<StaffRole>> roles = rolesOf(page);
        return new Slice<>(
                page.stream().map(user -> toMember(user, roles.getOrDefault(user.id(), Set.of()))).toList(),
                more ? position + limit : null);
    }

    @PreAuthorize(StaffAccess.READ)
    public StaffMember find(UUID id) {
        return load(id);
    }

    /**
     * Change the email address, the names, and whether they can sign in.
     *
     * <p>A new email address is unverified until they prove it; an unchanged one keeps its
     * verification. Disabling also ends their sessions — a disabled user's refresh tokens already
     * fail, and this takes the sessions off Keycloak's list too, so nothing looks signed in that
     * cannot be. Access tokens already issued last out their lifetime; nothing recalls a signed token.
     */
    @PreAuthorize(StaffAccess.ADMIN)
    public StaffMember update(UUID id, StaffChanges changes) {
        Optional<UUID> caller = caller();
        boolean disabling = changes.getEnabled().filter(enabled -> !enabled).isPresent();
        if (disabling) {
            refuseSelf(caller, id, "disable yourself");
        }
        requireCurrentSuperAdmin(caller);
        KeycloakUser current = staffUser(id);

        @Nullable String email = changes.getEmail()
                .filter(requested -> !requested.equalsIgnoreCase(current.email()))
                .orElse(null);
        @Nullable String firstName = changes.getFirstName().orElse(null);
        @Nullable String lastName = changes.getLastName().orElse(null);
        @Nullable Boolean enabled = changes.getEnabled()
                .filter(requested -> requested != current.enabled())
                .orElse(null);
        if (email != null || firstName != null || lastName != null || enabled != null) {
            switch (keycloak.update(id, email, firstName, lastName, enabled)) {
                case UPDATED -> log.info("Staff member {} updated by {}: email {}, names {}, enabled {}", id,
                        actor(caller), email != null, firstName != null || lastName != null, enabled);
                case NOT_FOUND -> throw notFound(id);
                case EMAIL_TAKEN -> throw new EmailTakenException();
            }
        }
        // Whenever a disable is asked for, not only when it changed something: a repeat of a disable
        // whose sign-out failed finds the user disabled already, and still has the sign-out to do.
        if (disabling) {
            keycloak.logout(id);
        }
        return load(id);
    }

    /**
     * Replace their staff roles with exactly these. Realm roles that are not staff roles are left
     * alone.
     *
     * <p>Taken away first, then granted. If the second call fails, the member holds less than either
     * set rather than more than both: a failure that removes access shows itself, and one that adds
     * access does not. Either way a repeat finishes it.
     *
     * <p>Takes effect at their next token. One already issued keeps its roles until it expires.
     */
    @PreAuthorize(StaffAccess.ADMIN)
    public StaffMember replaceRoles(UUID id, @NotEmpty Set<StaffRole> roles) {
        Optional<UUID> caller = caller();
        refuseSelf(caller, id, "change your own roles");
        requireCurrentSuperAdmin(caller);
        StaffMember current = load(id);
        Set<StaffRole> revoked = minus(current.getRoles(), roles);
        Set<StaffRole> granted = minus(roles, current.getRoles());
        keycloak.revokeRealmRoles(id, names(revoked));
        keycloak.grantRealmRoles(id, names(granted));
        if (!revoked.isEmpty() || !granted.isEmpty()) {
            log.info("Staff member {}: granted {}, revoked {} by {}", id, granted, revoked, actor(caller));
        }
        return load(id);
    }

    /**
     * Delete the staff member from Keycloak, sessions and all.
     *
     * <p>Final, and it loses who they were: anything that recorded their id can no longer resolve
     * it. Disabling them keeps the record and ends the access, which is usually what a leaver needs.
     */
    @PreAuthorize(StaffAccess.ADMIN)
    public void delete(UUID id) {
        Optional<UUID> caller = caller();
        refuseSelf(caller, id, "delete yourself");
        requireCurrentSuperAdmin(caller);
        staffUser(id);
        if (keycloak.delete(id)) {
            log.info("Staff member {} deleted by {}", id, actor(caller));
        }
    }

    // ------------------------------------------------------------------------------------------------

    /**
     * Keycloak answered 409. Decide whether the user holding the username is this request's own
     * earlier attempt: staff, with the email address this request asked for.
     */
    private KeycloakUser earlierAttempt(NewStaffMember command) {
        Optional<KeycloakUser> byUsername = keycloak.findByUsername(command.getUsername());
        if (byUsername.isEmpty()) {
            // The username is free, so the 409 was about the email address.
            throw new EmailTakenException();
        }
        KeycloakUser existing = byUsername.get();
        String email = existing.email();
        boolean ours = email != null
                && email.equalsIgnoreCase(command.getEmail())
                && keycloak.isStaff(existing.id());
        if (!ours) {
            throw new UsernameTakenException(command.getUsername());
        }
        return existing;
    }

    private StaffMember load(UUID id) {
        KeycloakUser user = staffUser(id);
        return toMember(user, staffRoles(keycloak.realmRoles(id)));
    }

    /** The Keycloak user, if they are staff. Anybody else is not found here, whoever they are. */
    private KeycloakUser staffUser(UUID id) {
        return keycloak.findById(id)
                .filter(user -> keycloak.isStaff(user.id()))
                .orElseThrow(() -> notFound(id));
    }

    private Map<UUID, Set<StaffRole>> rolesOf(List<KeycloakUser> page) {
        if (page.isEmpty()) {
            return Map.of();
        }
        Set<UUID> ids = page.stream().map(KeycloakUser::id).collect(Collectors.toSet());
        Map<UUID, Set<StaffRole>> roles = new HashMap<>();
        for (StaffRole role : StaffRole.values()) {
            for (UUID holder : keycloak.realmRoleHolders(role.name())) {
                if (ids.contains(holder)) {
                    roles.computeIfAbsent(holder, unused -> EnumSet.noneOf(StaffRole.class)).add(role);
                }
            }
        }
        return roles;
    }

    /**
     * Who is asking, as the Keycloak id their token's {@code sub} names.
     *
     * <p>Empty only for the local profile's anonymous principal: not a realm user at all, and so
     * never the target of anything here. Everything else must name its holder, because the
     * self-rules and the live check both need to know who the caller is, and an empty answer
     * switches them off. So a token without a usable {@code sub} is refused — Keycloak puts one in
     * only through the {@code basic} client scope, so a client configured without it would
     * otherwise do exactly that — and so is any authentication that is not a Keycloak token at all.
     * Today the resource server produces nothing else, but an opaque-token introspector or another
     * converter added later would, and it must fail closed here rather than open.
     */
    private static Optional<UUID> caller() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new AccessDeniedException("Staff changes need a Keycloak token that says who the caller is, "
                    + "and this request has none");
        }
        String subject = token.getToken().getSubject();
        if (subject != null) {
            try {
                return Optional.of(UUID.fromString(subject));
            } catch (IllegalArgumentException notAKeycloakId) {
                // Falls through to the refusal below.
            }
        }
        throw new AccessDeniedException("The token does not say who its holder is: it carries no Keycloak user id "
                + "in sub. Its client needs Keycloak's basic client scope.");
    }

    /** Refuse a change the caller would make to themselves. */
    private static void refuseSelf(Optional<UUID> caller, UUID target, String what) {
        if (caller.filter(target::equals).isPresent()) {
            throw new SelfChangeForbiddenException(what);
        }
    }

    /**
     * Refuse a caller who is no longer an enabled staff member holding {@code SUPER_ADMIN}, whatever
     * their token says. See the class comment for why writes ask Keycloak.
     */
    private void requireCurrentSuperAdmin(Optional<UUID> caller) {
        if (caller.isEmpty()) {
            return;
        }
        UUID id = caller.get();
        boolean current = keycloak.findById(id).filter(KeycloakUser::enabled).isPresent()
                && keycloak.isStaff(id)
                && keycloak.effectiveRealmRoles(id).contains(StaffRole.SUPER_ADMIN.name());
        if (!current) {
            log.warn("Refused a staff change by {}: not an enabled SUPER_ADMIN in Keycloak any more", id);
            throw new AccessDeniedException("You are no longer an enabled SUPER_ADMIN in Keycloak");
        }
    }

    /** The caller, for a log line. */
    private static String actor(Optional<UUID> caller) {
        return caller.map(UUID::toString).orElse("the local profile");
    }

    private static Set<StaffRole> staffRoles(List<String> realmRoles) {
        Set<StaffRole> roles = EnumSet.noneOf(StaffRole.class);
        realmRoles.stream().map(StaffRole::fromRealmRole).flatMap(Optional::stream).forEach(roles::add);
        return roles;
    }

    private static Set<StaffRole> minus(Set<StaffRole> from, Set<StaffRole> taken) {
        Set<StaffRole> rest = EnumSet.noneOf(StaffRole.class);
        rest.addAll(from);
        rest.removeAll(taken);
        return rest;
    }

    private static List<String> names(Set<StaffRole> roles) {
        return roles.stream().map(Enum::name).toList();
    }

    private static StaffMember toMember(KeycloakUser user, Set<StaffRole> roles) {
        Long created = user.createdTimestamp();
        return new StaffMember(
                user.id(),
                user.username(),
                user.email(),
                user.firstName(),
                user.lastName(),
                user.enabled(),
                roles,
                created == null ? null : Instant.ofEpochMilli(created));
    }

    private static ResourceNotFoundException notFound(UUID id) {
        return new ResourceNotFoundException("Staff member", id);
    }
}
