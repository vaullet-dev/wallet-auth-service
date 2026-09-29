package dev.vaullet.auth.common.error.exception;

import dev.vaullet.auth.common.error.AuthErrorType;
import dev.vaullet.common.error.ApplicationException;
import java.io.Serial;

/**
 * An identity arrived at a deployment that does not manage users — see
 * {@link AuthErrorType#USER_MANAGEMENT_UNAVAILABLE}.
 *
 * <p>Refused rather than ignored. Creating the account and quietly dropping the identity would
 * answer 201 to a request that did not happen as asked, and the caller would find out at the user's
 * first login.
 */
public class UserManagementUnavailableException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public UserManagementUnavailableException() {
        super(AuthErrorType.USER_MANAGEMENT_UNAVAILABLE,
                "This deployment does not manage users (auth.provider: federated); send the account without an identity");
    }
}
