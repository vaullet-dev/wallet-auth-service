package dev.vaullet.auth.common.error.exception;

import dev.vaullet.auth.common.error.AuthErrorType;
import dev.vaullet.common.error.ApplicationException;
import java.io.Serial;
import java.util.UUID;

/**
 * Refused because an email address is unverified — see {@link AuthErrorType#EMAIL_NOT_VERIFIED}:
 * a reset link that would go to an address the user has not proved while they can already sign in,
 * or an account at first sign-in for somebody who has not proved theirs.
 */
public class EmailNotVerifiedException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EmailNotVerifiedException(UUID accountId) {
        this("Account %s can already sign in and its email address is unverified, so no reset link was sent"
                .formatted(accountId));
    }

    private EmailNotVerifiedException(String detail) {
        super(AuthErrorType.EMAIL_NOT_VERIFIED, detail);
    }

    /** {@code PUT /v1/accounts/me} by somebody whose email address is not verified yet. */
    public static EmailNotVerifiedException beforeOwnAccount() {
        return new EmailNotVerifiedException("Your email address is not verified yet, and an account is opened "
                + "only for a verified one. Follow the link in the email Keycloak sent you, then sign in again");
    }
}
