package dev.vaullet.auth.staff.service;

/**
 * The {@code @PreAuthorize} rules of staff management, each written once.
 *
 * <p>{@code SUPER_ADMIN} for everything, reads included: ADR-006 gives user and role administration
 * to that role alone, and the staff list — who holds {@code FINANCE}, who can freeze an account — is
 * part of administering roles rather than something every support agent needs.
 *
 * <p>The scopes are the identity scopes {@code /v1/accounts} already uses, so a client configured
 * for user management needs nothing new. ADR-008's scope table needs a row for {@code /v1/staff/**}
 * all the same.
 *
 * <p>These read the token. For writes, {@code StaffService} also asks Keycloak whether the caller
 * still is what the token says — a role taken away, or an account disabled, a minute ago is still in
 * a token issued before that.
 */
final class StaffAccess {

    /** Listing and reading staff. */
    static final String READ = "hasAuthority('SCOPE_identity:read') and hasRole('SUPER_ADMIN')";

    /** Creating, changing, re-roling and deleting staff. */
    static final String ADMIN = "hasAuthority('SCOPE_identity:admin') and hasRole('SUPER_ADMIN')";

    private StaffAccess() {}
}
