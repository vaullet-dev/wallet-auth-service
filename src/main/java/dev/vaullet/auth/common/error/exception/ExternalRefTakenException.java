package dev.vaullet.auth.common.error.exception;

import dev.vaullet.auth.common.error.AuthErrorType;
import dev.vaullet.common.error.ApplicationException;
import java.io.Serial;

/**
 * Another account already carries the requested {@code external_ref}, behind a different identity.
 *
 * <p>Not raised for a plain repeat. A second {@code POST /v1/accounts} with the same
 * {@code external_ref} and nothing contradicting it is a retry, and returns the existing account —
 * see {@link AuthErrorType#EXTERNAL_REF_TAKEN}. This is the genuine collision: two identities
 * claiming one operator identifier.
 *
 * <p>The message repeats the reference the caller just sent and nothing else. It is the operator's
 * own identifier, so telling them it is taken leaks nothing they do not already know — but naming
 * the <em>account</em> holding it would hand them a cross-system lookup nobody granted.
 */
public class ExternalRefTakenException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ExternalRefTakenException(String externalRef) {
        super(AuthErrorType.EXTERNAL_REF_TAKEN, "External reference '%s' is already in use".formatted(externalRef));
    }
}
