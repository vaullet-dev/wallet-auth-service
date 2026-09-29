package dev.vaullet.auth.common.error;

import dev.vaullet.common.error.CommonErrorType;
import dev.vaullet.common.error.ErrorType;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * The error codes that belong to identity, alongside the platform ones in {@link CommonErrorType}.
 *
 * <p>The membership test {@code backend-common-core} applies is "would a service that knows nothing
 * about this domain still raise it". Every entry below fails it — none of them mean anything to a
 * service with no notion of an account — so they live here rather than in the shared catalogue.
 *
 * <h2>Why specific codes and not one</h2>
 *
 * <p>Every conflict here could have been {@link CommonErrorType#RESOURCE_CONFLICT}, and that would
 * have been technically correct and practically useless. An integrator that gets a bare 409 from
 * {@code POST /v1/accounts} has several different problems to tell apart — their own identifier is
 * already mapped, the identity is already mapped, the account is closed, the username or the email
 * address belongs to someone else — and each has a different fix, only one of which is "retry". A
 * code the caller can branch on is the difference between a self-service integration and a support
 * ticket.
 *
 * <p>The conflicts are 409 rather than 422: the request is well formed and would be valid against
 * different state. That distinction is the one ADR-011 §7 draws, and it is worth keeping honest —
 * a 422 tells an integrator to fix their payload, which for a conflict would send them looking for a
 * bug that is not in their code. The two 422s below are the cases where the payload <em>is</em> the
 * problem.
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
    ACCOUNT_CLOSED("account-closed", HttpStatus.CONFLICT, "Account is closed"),

    /**
     * Keycloak already has a user with this username, and it is not one this service created for this
     * request.
     *
     * <p>A repeat of an earlier create is not this: the service recognises its own user by the
     * {@code account_id} it wrote and the email address it sent, and answers with the account. This
     * code is for the username belonging to somebody else — another end user, a staff account, or a
     * user who registered through Keycloak's own pages.
     */
    USERNAME_TAKEN("username-taken", HttpStatus.CONFLICT, "Username already taken"),

    /**
     * Keycloak already has a user with this email address. The realm keeps addresses unique, which is
     * what makes password reset and login by email mean one person.
     */
    EMAIL_TAKEN("email-taken", HttpStatus.CONFLICT, "Email address already in use"),

    /**
     * Keycloak refused the identity itself: a password that fails the realm's password policy, or a
     * value one of the realm's user-profile validators rejects.
     *
     * <p>422 rather than 409, the distinction ADR-011 §7 draws: the same values would fail against any
     * state, so the fix is in the payload. The detail carries Keycloak's own reason.
     */
    IDENTITY_REJECTED("identity-rejected", HttpStatus.valueOf(422), "Identity rejected by the identity provider"),

    /**
     * The request carried an identity, and this deployment does not manage users: it runs with
     * {@code auth.provider: federated}, where identities come from the operator's own identity
     * provider (ADR-014 §2). The fix is to send the account without {@code identity}.
     */
    USER_MANAGEMENT_UNAVAILABLE(
            "user-management-unavailable", HttpStatus.valueOf(422), "User management is not available in this deployment");

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
