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
     * role added to the realm later, is refused until someone decides otherwise. An end user reads
     * their own account through {@link #SELF} instead, and operator backends on client credentials
     * arrive in step 5 and become a clause here when they do.
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

    /**
     * An end user's own account, and nobody else's: {@code GET} and {@code PUT /v1/accounts/me}.
     *
     * <p>{@code identity:self}, and no role. The user this exists for holds none yet: {@code PUT}
     * is what grants {@code END_USER}, to somebody who registered on Keycloak's own pages a minute
     * ago. What keeps it to end users is the method behind it, which asks Keycloak about the caller
     * and refuses staff. What keeps it to the caller is this rule: the method takes the account's
     * Keycloak user id as a parameter named {@code subject}, and the rule insists it is the caller's
     * own ({@code authentication.name} is the token's {@code sub}). The controller passes exactly
     * that, and checking it here as well means no later caller of the method can pass somebody
     * else's by mistake.
     */
    static final String SELF =
            "hasAuthority('SCOPE_identity:self') and #subject.toString() == authentication.name";

    private AccountAccess() {}
}
