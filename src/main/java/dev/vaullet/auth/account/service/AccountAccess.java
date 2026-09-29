package dev.vaullet.auth.account.service;

/**
 * The {@code @PreAuthorize} rules the account services share, each written once.
 *
 * <p>Constants rather than a bean the expressions call, so every rule stays readable on the method
 * it guards and is checked by the compiler: an annotation value has to be a compile-time constant,
 * and these are.
 */
final class AccountAccess {

    /**
     * Who may read an account or its identity: {@code identity:read} <em>and</em> one of ADR-006's
     * staff roles — the README's "{@code SUPPORT_AGENT} and up".
     *
     * <p>Both, because a scope says what the <em>client</em> may ask for, not who the user is:
     * Keycloak puts a client scope in the token of everyone who signs in through a client it is
     * attached to, {@code END_USER} included (#15).
     *
     * <p>An allow-list rather than "anyone but {@code END_USER}": a token with no role at all, or a
     * role added to the realm later, is refused until someone decides otherwise. An end user reading
     * their own account arrives with the {@code account_id} claim in step 2, and operator backends on
     * client credentials in step 5; each becomes a clause here when it does.
     */
    static final String READ = "hasAuthority('SCOPE_identity:read') and hasAnyRole("
            + "'SUPPORT_AGENT', 'FRAUD_REVIEWER', 'FINANCE', 'CONFIG_ADMIN', 'SUPER_ADMIN')";

    /**
     * User administration: creating accounts and end users, changing an identity, closing an account
     * and erasing its identity. {@code SUPER_ADMIN}'s, per ADR-006's role table.
     */
    static final String USER_ADMIN = "hasAuthority('SCOPE_identity:admin') and hasRole('SUPER_ADMIN')";

    /**
     * The fraud freeze. Deliberately a different role from {@link #USER_ADMIN}: ADR-006's separation
     * of duties means the people who administer users are not the people who freeze them.
     */
    static final String FREEZE = "hasAuthority('SCOPE_identity:admin') and hasRole('FRAUD_REVIEWER')";

    private AccountAccess() {}
}
