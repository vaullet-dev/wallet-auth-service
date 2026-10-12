package dev.vaullet.auth.common.error.exception;

import dev.vaullet.auth.common.error.AuthErrorType;
import dev.vaullet.common.error.ApplicationException;
import java.io.Serial;
import java.util.UUID;

/**
 * A reset link would go to an address the user has not proved, and they can already sign in — see
 * {@link AuthErrorType#EMAIL_NOT_VERIFIED}.
 */
public class EmailNotVerifiedException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EmailNotVerifiedException(UUID accountId) {
        super(AuthErrorType.EMAIL_NOT_VERIFIED,
                "Account %s can already sign in and its email address is unverified, so no reset link was sent"
                        .formatted(accountId));
    }
}
