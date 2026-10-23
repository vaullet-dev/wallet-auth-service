package dev.vaullet.auth.common.error.exception;

import dev.vaullet.auth.common.error.AuthErrorType;
import dev.vaullet.common.error.ApplicationException;
import java.io.Serial;

/**
 * A member of staff asked for an account of their own — see
 * {@link AuthErrorType#STAFF_ACCOUNT_FORBIDDEN}.
 */
public class StaffAccountForbiddenException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public StaffAccountForbiddenException() {
        super(AuthErrorType.STAFF_ACCOUNT_FORBIDDEN,
                "Staff have no account: an account holds an end user's money, and staff hold none");
    }
}
