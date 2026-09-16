package dev.vaullet.auth.common.error;

import dev.vaullet.common.error.CommonErrorType;
import dev.vaullet.common.security.SecurityApiExceptionHandler;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * The shared exception handler, with one backstop added.
 *
 * <p>A subclass of {@link SecurityApiExceptionHandler} rather than a second advice, which is the
 * extension point the library documents: Spring consults advices in order, the base class carries a
 * catch-all {@code @ExceptionHandler(Exception.class)}, and a separate advice would need an
 * {@code @Order} to beat it. Subclassing sidesteps the ordering problem entirely — a more specific
 * handler in the same advice always wins.
 *
 * <p>Declaring the bean is also what makes both auto-configurations back off, so there is exactly one
 * advice producing error bodies.
 *
 * <p><b>No {@code lockContentionErrorType()} override.</b> The ledger renames {@code RESOURCE_BUSY}
 * to {@code ACCOUNT_BUSY} because that code was published in its contract before the platform one
 * existed, and renaming a published code is a breaking change under ADR-011 §4. This service has no
 * such history, so it inherits the platform code and a reader has one less local exception to learn.
 *
 * <p>Everything else — the single {@code ApplicationException} handler that covers all three
 * exceptions in this package, the validation bodies, the opaque 500, the {@code AccessDeniedException}
 * mapping — comes from the library. There is deliberately no equivalent of the ledger's
 * {@code LedgerErrorBridge}, whose own Javadoc calls it scaffolding meant to be deleted.
 */
@RestControllerAdvice
class AuthApiExceptionHandler extends SecurityApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthApiExceptionHandler.class);

    AuthApiExceptionHandler(ObjectProvider<Tracer> tracer) {
        super(tracer);
    }

    /**
     * The losing side of a concurrent create.
     *
     * <p>{@code AccountService} checks {@code external_ref} and {@code keycloak_sub} inside its
     * transaction and raises a specific, actionable code for each. That check cannot close the window
     * entirely: two requests can both pass it and race to the insert, and the database — correctly —
     * refuses the second.
     *
     * <p>Without this method that refusal reaches the catch-all and becomes a 500, which is the wrong
     * answer twice over: the service is healthy, and the caller's request genuinely conflicts. A
     * generic {@code RESOURCE_CONFLICT} is honest here — the specific codes are for the case the
     * service could diagnose, and this is the case it could not.
     *
     * <p>Warn rather than error: it is rare and self-inflicted by concurrent callers, but a rising
     * rate means something is retrying without backoff and is worth seeing.
     */
    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail handleDuplicateKey(DuplicateKeyException ex, WebRequest request) {
        log.warn("Lost a concurrent insert race on a unique constraint", ex);
        return problem(
                CommonErrorType.RESOURCE_CONFLICT,
                "That account already exists",
                request);
    }
}
