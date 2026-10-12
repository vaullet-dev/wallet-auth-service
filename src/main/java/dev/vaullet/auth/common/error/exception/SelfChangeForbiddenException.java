package dev.vaullet.auth.common.error.exception;

import dev.vaullet.auth.common.error.AuthErrorType;
import dev.vaullet.common.error.ApplicationException;
import java.io.Serial;

/**
 * A staff member tried to change their own roles, disable themselves or delete themselves — see
 * {@link AuthErrorType#SELF_CHANGE_FORBIDDEN}.
 */
public class SelfChangeForbiddenException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** @param what the refused change, worded to follow "You cannot" */
    public SelfChangeForbiddenException(String what) {
        super(AuthErrorType.SELF_CHANGE_FORBIDDEN,
                "You cannot " + what + "; another SUPER_ADMIN has to do it");
    }
}
