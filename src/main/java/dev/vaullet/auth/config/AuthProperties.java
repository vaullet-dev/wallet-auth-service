package dev.vaullet.auth.config;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * This service's own settings under {@code auth.*}: ADR-014 §9's Helm values, as the application
 * reads them.
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
 *     {@code local} mode only.
 * @param userManagement how this service manages the users it keeps. Read in {@code local} mode
 *     only.
 */
@ConfigurationProperties("auth")
public record AuthProperties(
        @DefaultValue("local") Provider provider,
        @DefaultValue Keycloak keycloak,
        @DefaultValue UserManagement userManagement) {

    public enum Provider {
        LOCAL,
        FEDERATED
    }

    /**
     * @param url Keycloak's base URL, including its relative path ({@code /auth} on vaullet.dev).
     *     The in-cluster Service rather than the public host: the gateway route to
     *     {@code /auth/admin/} is one that should be closed, and this service must not depend on it.
     * @param realm the realm end users live in.
     * @param clientId the confidential client this service authenticates as. Its service account
     *     holds realm-management {@code manage-users}, {@code view-users} and {@code view-realm}, and
     *     nothing else.
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
}
