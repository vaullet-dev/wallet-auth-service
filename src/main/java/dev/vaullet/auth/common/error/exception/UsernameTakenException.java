package dev.vaullet.auth.common.error.exception;

import dev.vaullet.auth.common.error.AuthErrorType;
import dev.vaullet.common.error.ApplicationException;
import java.io.Serial;

/**
 * Keycloak already has a user with the requested username, and it is not this request's own earlier
 * attempt — see {@link AuthErrorType#USERNAME_TAKEN}.
 *
 * <p>The username is repeated because the caller just sent it. Nothing about the user who holds it
 * is.
 */
public class UsernameTakenException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public UsernameTakenException(String username) {
        super(AuthErrorType.USERNAME_TAKEN, "Username '%s' is already taken".formatted(username));
    }
}
