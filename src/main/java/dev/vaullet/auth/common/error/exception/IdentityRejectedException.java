package dev.vaullet.auth.common.error.exception;

import dev.vaullet.auth.common.error.AuthErrorType;
import dev.vaullet.common.error.ApplicationException;
import java.io.Serial;

/**
 * Keycloak refused the identity values themselves — see {@link AuthErrorType#IDENTITY_REJECTED}.
 *
 * <p>The reason is Keycloak's own message key, such as {@code invalidPasswordMinLengthMessage} or
 * {@code error-invalid-email}: stable enough to branch on, and free of the values that were sent.
 */
public class IdentityRejectedException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public IdentityRejectedException(String keycloakReason) {
        super(AuthErrorType.IDENTITY_REJECTED, keycloakReason.isBlank()
                ? "Keycloak rejected the identity"
                : "Keycloak rejected the identity: " + keycloakReason);
    }
}
