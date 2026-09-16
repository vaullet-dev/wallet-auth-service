package dev.vaullet.auth.common.error.exception;

import dev.vaullet.auth.common.error.AuthErrorType;
import dev.vaullet.common.error.ApplicationException;
import java.io.Serial;
import java.util.UUID;

/**
 * A closed account cannot be changed. Closure is terminal (ADR-014 §7).
 *
 * <p>Raised by the service <em>before</em> the write, so the caller gets this rather than the
 * database trigger's {@code DataIntegrityViolationException}. The trigger stays as the backstop for
 * every other code path — both refuse, but only one of them explains.
 *
 * <p>The account id is in the message because the caller supplied it in the path; it is their own
 * identifier coming back to them.
 */
public class AccountClosedException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public AccountClosedException(UUID accountId) {
        super(AuthErrorType.ACCOUNT_CLOSED, "Account %s is closed".formatted(accountId));
    }
}
