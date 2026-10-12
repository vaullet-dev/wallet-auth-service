package dev.vaullet.auth.staff.service;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One of the operator's staff, as Keycloak holds them: read on every request, stored nowhere by
 * Vaullet (ADR-014 §4 applies to staff as it does to end users).
 *
 * <p><b>No account.</b> Staff have no wallet, so no anchor and no {@code account_id}; the id is
 * Keycloak's own. That is the coupling ADR-006 keeps away from the ledger, accepted here because
 * nothing financial is keyed on a staff member — only the audit trail names them, and that names the
 * {@code sub} in their token, which is this id.
 */
public final class StaffMember {

    private final UUID id;
    private final String username;
    private final @Nullable String email;
    private final @Nullable String firstName;
    private final @Nullable String lastName;
    private final boolean enabled;
    private final Set<StaffRole> roles;
    private final @Nullable Instant createdAt;

    StaffMember(
            UUID id,
            String username,
            @Nullable String email,
            @Nullable String firstName,
            @Nullable String lastName,
            boolean enabled,
            Set<StaffRole> roles,
            @Nullable Instant createdAt) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.enabled = enabled;
        this.roles = roles.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.noneOf(StaffRole.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(roles));
        this.createdAt = createdAt;
    }

    /** Keycloak's user id, and the {@code sub} of this person's tokens. */
    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public Optional<String> getEmail() {
        return Optional.ofNullable(email);
    }

    public Optional<String> getFirstName() {
        return Optional.ofNullable(firstName);
    }

    public Optional<String> getLastName() {
        return Optional.ofNullable(lastName);
    }

    /** Whether they can sign in. Disabling is how a leaver is switched off without losing the record. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Their staff roles, in declaration order. Empty only for a member whose creation stopped before
     * the roles were granted — {@code PUT /v1/staff/{id}/roles} or a repeat of the create finishes it.
     */
    public Set<StaffRole> getRoles() {
        return roles;
    }

    public Optional<Instant> getCreatedAt() {
        return Optional.ofNullable(createdAt);
    }

    /** The id and the roles: everything else is personal data. */
    @Override
    public String toString() {
        return "StaffMember[id=" + id + ", roles=" + roles + ", enabled=" + enabled + "]";
    }
}
