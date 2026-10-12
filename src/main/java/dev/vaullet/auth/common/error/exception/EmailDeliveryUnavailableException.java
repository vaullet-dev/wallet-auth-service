package dev.vaullet.auth.common.error.exception;

import dev.vaullet.auth.common.error.AuthErrorType;
import dev.vaullet.common.error.ApplicationException;
import java.io.Serial;

/**
 * The realm has no SMTP server, so the email this request depends on cannot be sent — see
 * {@link AuthErrorType#EMAIL_DELIVERY_UNAVAILABLE}.
 */
public class EmailDeliveryUnavailableException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EmailDeliveryUnavailableException() {
        super(AuthErrorType.EMAIL_DELIVERY_UNAVAILABLE,
                "Keycloak's realm has no SMTP server, so the user cannot be emailed a link to set their password. "
                        + "Nothing was changed; repeat the request once email is configured");
    }
}
