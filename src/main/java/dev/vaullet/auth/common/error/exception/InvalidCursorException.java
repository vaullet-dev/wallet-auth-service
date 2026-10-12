package dev.vaullet.auth.common.error.exception;

import dev.vaullet.common.error.ApplicationException;
import dev.vaullet.common.error.CommonErrorType;
import java.io.Serial;

/**
 * A {@code cursor} query parameter this API did not issue. The platform's
 * {@code VALIDATION_FAILED}: the parameter is malformed, and the fix is to send back the
 * {@code next_cursor} of an earlier page, or none for the first.
 */
public class InvalidCursorException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidCursorException() {
        super(CommonErrorType.VALIDATION_FAILED,
                "cursor is not one this API issued: send the next_cursor of an earlier page, or none for the first");
    }
}
