package dev.vaullet.auth.common.error;

import dev.vaullet.common.error.CommonErrorType;
import dev.vaullet.common.error.ErrorType;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * The error codes that belong to identity, alongside the platform ones in {@link CommonErrorType}.
 *
 * <p>The membership test {@code backend-common-core} applies is "would a service that knows nothing
 * about this domain still raise it". All three below fail it — none of them mean anything to a
 * service with no notion of an account — so they live here rather than in the shared catalogue.
 *
 * <h2>Why three codes and not one</h2>
 *
 * <p>Every one of these could have been {@link CommonErrorType#RESOURCE_CONFLICT}, and that would
 * have been technically correct and practically useless. An integrator that gets a bare 409 from
 * {@code POST /v1/accounts} has three different problems to tell apart — their own identifier is
 * already mapped, the identity is already mapped, or the account is closed — and each has a
 * different fix, only one of which is "retry". A code the caller can branch on is the difference
 * between a self-service integration and a support ticket.
 *
 * <p>All three are 409 rather than 422: the request is well formed and would be valid against
 * different state. That distinction is the one ADR-011 §7 draws, and it is worth keeping honest —
 * a 422 tells an integrator to fix their payload, which here would send them looking for a bug that
 * is not in their code.
 *
 * <p>Adding an entry is additive and safe. Changing what an existing entry <em>means</em> is a
 * breaking change for every caller that branches on it, and needs a new major API version
 * (ADR-011, §4).
 */
public enum AuthErrorType implements ErrorType {

    /**
     * Another account already carries this {@code external_ref}, with a different identity behind it.
     *
     * <p>ADR-012 promises operators that the reference is unique per deployment, so this is the
     * promise being kept rather than an internal failure.
     *
     * <p><b>It is deliberately narrow.</b> A repeat {@code POST} carrying the same
     * {@code external_ref} and nothing contradicting it is a <em>retry</em>, and returns the existing
     * account — the natural key is what makes creation idempotent without an {@code Idempotency-Key}.
     * This code fires only when the second request disagrees with the first, which is a genuinely
     * different claim on one operator identifier rather than a repeated one.
     */
    EXTERNAL_REF_TAKEN("external-ref-taken", HttpStatus.CONFLICT, "External reference already in use"),

    /**
     * Another account is already linked to this Keycloak subject.
     *
     * <p>ADR-006 lists the failure this guards against by name: a subject collision after an operator
     * migrates identity provider, producing two accounts for one person and splitting their balance
     * across both. Refusing the second link is the only safe answer — re-pointing an anchor is a
     * deliberate operation with a migration behind it, never a side effect of an ordinary create.
     */
    IDENTITY_ALREADY_LINKED("identity-already-linked", HttpStatus.CONFLICT, "Identity already linked"),

    /**
     * The account is {@code CLOSED}, and closure is terminal (ADR-014 §7).
     *
     * <p>A 409 rather than a 404. The account exists, and the caller is entitled to know it exists —
     * hiding it would make "does this user exist" answer differently depending on <em>why</em>, which
     * is worse for the integrator and no better for an attacker who can already enumerate their own
     * {@code external_ref}s.
     *
     * <p>The database enforces this too, in the {@code accounts_invariants} trigger, because a
     * reopened account would contradict the audit trail its closure produced. The service raises this
     * first so the caller gets an explanation; the trigger is the backstop for every other code path.
     * Both refuse, but only one of them says why.
     */
    ACCOUNT_CLOSED("account-closed", HttpStatus.CONFLICT, "Account is closed");

    private final URI type;
    private final HttpStatus status;
    private final String title;

    AuthErrorType(String slug, HttpStatus status, String title) {
        this.type = ErrorType.documentationUri(slug);
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public URI type() {
        return type;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
