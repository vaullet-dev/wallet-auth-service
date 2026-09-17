package dev.vaullet.auth.account.service;

/**
 * The lifecycle of an account — ADR-006's fraud lock, and ADR-014's renaming of it.
 *
 * <p>Authoritative here and nowhere else. Keycloak has its own {@code enabled} flag and its own
 * brute-force lockout, and neither is this: a fraud lock the operator's directory could overrule is
 * not a fraud lock. That is why the column lives in a Vaullet table even in a fully federated
 * deployment.
 *
 * <p>The values are also the database's {@code accounts_status_ck}. Adding one means a migration and
 * a published API change, not just an enum constant.
 */
public enum AccountStatus {

    /** The normal state. Created this way; nothing else creates an account. */
    ACTIVE,

    /**
     * Frozen. Reached by a {@code FRAUD_REVIEWER} or by {@code risk.account-locked} from Risk
     * Management, and reversible by a {@code FRAUD_REVIEWER}.
     *
     * <p>ADR-006 called this {@code LOCKED}. ADR-014 renamed it, because ADR-012 had already
     * published {@code SUSPENDED} to operators <em>and</em> because Keycloak uses "locked" for a user
     * its own brute-force detection locked out — a different thing entirely, in the same deployment.
     */
    SUSPENDED,

    /**
     * Terminal. Closure erases the Keycloak identity and leaves every ledger entry intact, which is
     * how GDPR erasure and seven-year retention coexist (ADR-014 §7).
     *
     * <p>A closed account does not reopen: the audit trail its closure produced would then describe
     * something that is no longer true. A returning user gets a new {@code account_id}. The database
     * enforces this in the {@code accounts_invariants} trigger, so it holds for every code path and
     * not only for this one.
     */
    CLOSED;

    /** Parses the raw column value. A value outside this enum means the database drifted from us. */
    static AccountStatus of(String column) {
        return valueOf(column);
    }
}
