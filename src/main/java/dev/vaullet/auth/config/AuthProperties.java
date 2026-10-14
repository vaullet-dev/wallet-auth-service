package dev.vaullet.auth.config;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * This service's own settings under {@code auth.*}: ADR-014 §9's Helm values, as the application
 * reads them, and the outbox relay's (#39).
 *
 * <p><b>{@code local} is the default</b>: Vaullet keeps the users, in its own Keycloak realm, and this
 * service manages them. It cannot start half-configured: the Admin API client refuses to be built
 * without {@code auth.keycloak.url} and {@code auth.keycloak.client-secret}, so a deployment that left
 * them out fails at startup rather than serving without user management. {@code federated} is
 * deferred; it still starts, and runs the anchor alone.
 *
 * @param provider {@code local}: this service provisions end users in Keycloak itself.
 *     {@code federated}: identities come from the operator's own identity provider, and the
 *     user-management endpoints do not exist.
 * @param keycloak where the Admin API is, and which client this service authenticates as. Read in
 *     {@code local} mode, and by the outbox relay in either mode: its ledger token is this client's.
 * @param userManagement how this service manages the users it keeps. Read in {@code local} mode
 *     only.
 * @param ledger where the ledger is, for the outbox relay. Read in both modes: every account gets
 *     its wallets, whoever holds its identity.
 * @param outbox the account-created outbox and the relay that delivers it (#39).
 */
@ConfigurationProperties("auth")
public record AuthProperties(
        @DefaultValue("local") Provider provider,
        @DefaultValue Keycloak keycloak,
        @DefaultValue UserManagement userManagement,
        @DefaultValue Ledger ledger,
        @DefaultValue Outbox outbox) {

    public enum Provider {
        LOCAL,
        FEDERATED
    }

    /**
     * @param url Keycloak's base URL, including its relative path ({@code /auth} on vaullet.dev).
     *     The in-cluster Service rather than the public host: the gateway route to
     *     {@code /auth/admin/} is one that should be closed, and this service must not depend on it.
     *     The outbox relay's token endpoint is under it too.
     * @param realm the realm end users live in.
     * @param clientId the confidential client this service authenticates as. Its service account
     *     holds realm-management {@code manage-users}, {@code view-users} and {@code view-realm}, and
     *     nothing else; {@code ledger:write} is an optional scope of it, which only the outbox
     *     relay's token asks for.
     * @param clientSecret that client's secret, from OpenBao through External Secrets.
     * @param timeout connect and read timeout for every call to Keycloak.
     */
    public record Keycloak(
            @Nullable String url,
            @DefaultValue("vaullet") String realm,
            @DefaultValue("wallet-auth-service") String clientId,
            @Nullable String clientSecret,
            @DefaultValue("5s") Duration timeout) {

        /** The secret stays out of logs and error messages, whoever ends up printing this. */
        @Override
        public String toString() {
            boolean secretSet = clientSecret != null && !clientSecret.isBlank();
            return "Keycloak[url=" + url
                    + ", realm=" + realm
                    + ", clientId=" + clientId
                    + ", clientSecret=" + (secretSet ? "<redacted>" : "<unset>")
                    + ", timeout=" + timeout + "]";
        }
    }

    /**
     * ADR-014 §9's {@code auth.user-management} block, as far as it has been built.
     *
     * @param emailLinkLifespan how long the link in an invitation or a password-reset email works.
     *     Passed to Keycloak with every email, so the realm's own default — 12 hours for an email an
     *     admin triggers — does not apply.
     */
    public record UserManagement(@DefaultValue("72h") Duration emailLinkLifespan) {}

    /**
     * The ledger, as far as the outbox relay calls it.
     *
     * @param url the ledger's in-cluster Service, e.g. {@code http://ledger-service.wallet-ledger.svc}.
     *     There is no other way in: the ledger has no public route, because ADR-008 has only services
     *     call it. Required while the relay is on.
     * @param timeout connect and read timeout for every call to the ledger.
     */
    public record Ledger(@Nullable String url, @DefaultValue("5s") Duration timeout) {}

    /** @param relay what delivers the outbox's entries. */
    public record Outbox(@DefaultValue Relay relay) {

        /**
         * @param enabled whether this instance delivers. Off, entries are still written and simply
         *     wait; switching it on again delivers everything queued meanwhile. The relay's beans
         *     are conditional on it, so off they do not exist and ask for no ledger address.
         * @param pollInterval the pause between the end of one delivery round and the start of the
         *     next.
         * @param batchSize how many due entries one round claims.
         * @param lease how long a claimed entry is kept from every other relay. It must outlast the
         *     longest one delivery can take — the timeouts decide that — and the relay refuses to
         *     start otherwise.
         * @param initialBackoff the wait after an entry's first failed attempt. It doubles with each
         *     further failure...
         * @param maxBackoff ...up to this, and stays there: an entry is retried for as long as it
         *     takes, never given up on.
         * @param sweepInterval how often the relay queues the entry of any account that is not
         *     {@code CLOSED} and has none, after doing so once at start: the previous version's
         *     accounts during a rollout, and any path that forgot.
         */
        public record Relay(
                @DefaultValue("true") boolean enabled,
                @DefaultValue("5s") Duration pollInterval,
                @DefaultValue("20") int batchSize,
                @DefaultValue("2m") Duration lease,
                @DefaultValue("10s") Duration initialBackoff,
                @DefaultValue("15m") Duration maxBackoff,
                @DefaultValue("10m") Duration sweepInterval) {}
    }
}
