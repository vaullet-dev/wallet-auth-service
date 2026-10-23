package dev.vaullet.auth.account.api.v1;

import java.security.Principal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Who is calling, for the endpoints about the caller's own account: {@code /v1/accounts/me}. */
final class Callers {

    private Callers() {}

    /**
     * The caller's Keycloak user id: the {@code sub} of their token.
     *
     * <p>Anything else is a 403 rather than a lookup. The {@code local} profile's anonymous caller
     * holds every scope and role the rules ask for and is nobody, so it has no account to find or
     * create; nor has a token whose {@code sub} is not a Keycloak user id, which is what a client
     * without Keycloak's {@code basic} client scope sends. An authentication that is not a Keycloak
     * token at all is refused the same way, so that whatever else is added to the filter chain later
     * fails closed here rather than open. The service method checks again that the subject it is
     * given is the caller's own, so a future caller of it cannot pass somebody else's by mistake.
     */
    static UUID subject(@Nullable Principal caller) {
        if (caller instanceof JwtAuthenticationToken token) {
            String subject = token.getToken().getSubject();
            if (subject != null) {
                try {
                    return UUID.fromString(subject);
                } catch (IllegalArgumentException notAKeycloakId) {
                    // Refused below, like a missing one.
                }
            }
            throw new AccessDeniedException("The token does not say who its holder is: it carries no Keycloak "
                    + "user id in sub. Its client needs Keycloak's basic client scope.");
        }
        throw new AccessDeniedException("Your own account needs your own Keycloak token");
    }
}
