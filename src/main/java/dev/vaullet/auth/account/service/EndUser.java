package dev.vaullet.auth.account.service;

/**
 * An end user as a search finds them: the account Vaullet keeps and the identity Keycloak keeps,
 * read in the same request (ADR-014 §1 — the two objects that describe one person).
 */
public final class EndUser {

    private final Account account;
    private final Identity identity;

    EndUser(Account account, Identity identity) {
        this.account = account;
        this.identity = identity;
    }

    public Account getAccount() {
        return account;
    }

    public Identity getIdentity() {
        return identity;
    }

    /** The account id: the identity is personal data. */
    @Override
    public String toString() {
        return "EndUser[accountId=" + account.getAccountId() + "]";
    }
}
