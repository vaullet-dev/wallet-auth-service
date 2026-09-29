package dev.vaullet.auth.common.error.exception;

import dev.vaullet.auth.common.error.AuthErrorType;
import dev.vaullet.common.error.ApplicationException;
import java.io.Serial;

/**
 * Another Keycloak user already has the requested email address — see
 * {@link AuthErrorType#EMAIL_TAKEN}.
 *
 * <p>Unlike {@link UsernameTakenException}, the message does not repeat the value. Problem details
 * end up in logs on both sides of the API, and an email address is personal data where a username
 * usually is not.
 */
public class EmailTakenException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EmailTakenException() {
        super(AuthErrorType.EMAIL_TAKEN, "That email address is already in use");
    }
}
