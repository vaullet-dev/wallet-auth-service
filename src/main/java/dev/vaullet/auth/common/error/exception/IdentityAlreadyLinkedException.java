package dev.vaullet.auth.common.error.exception;

import dev.vaullet.auth.common.error.AuthErrorType;
import dev.vaullet.common.error.ApplicationException;
import java.io.Serial;
import java.util.UUID;

/**
 * Another account is already linked to this Keycloak subject — ADR-006's subject collision.
 *
 * <p>Neither identifier appears in the message, and that is deliberate rather than cautious. The
 * subject belongs to Keycloak and the account belongs to us; a caller holding one of them should not
 * learn the other from an error body, because that is a cross-system join nobody authorised.
 *
 * <p>The constructor takes the subject anyway. It is not interpolated into the message, but having
 * it at the throw site keeps the call readable and leaves one place to add structured logging if the
 * collision ever needs investigating.
 */
public class IdentityAlreadyLinkedException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient UUID keycloakSub;

    public IdentityAlreadyLinkedException(UUID keycloakSub) {
        super(AuthErrorType.IDENTITY_ALREADY_LINKED, "That identity is already linked to an account");
        this.keycloakSub = keycloakSub;
    }

    /** For logging and tests. Never reaches a response body. */
    public UUID keycloakSub() {
        return keycloakSub;
    }
}
