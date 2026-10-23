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
 * bug that is not in their code. The 422s below are the cases where the payload <em>is</em> the
 * problem, or asks for something this deployment cannot do.
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
            "user-management-unavailable", HttpStatus.valueOf(422), "User management is not available in this deployment"),

    /**
     * This deployment cannot send email yet: Keycloak's realm has no SMTP server.
     *
     * <p>An end user is invited by email and sets their own password from the link, so none passes
     * through Vaullet — and an invitation that cannot be sent is a user who cannot sign in. Refused
     * before anything is written, so nothing was created and the same request succeeds once the
     * realm has an SMTP server. 422 like {@link #USER_MANAGEMENT_UNAVAILABLE}: a capability this
     * deployment lacks, which no retry fixes.
     */
    EMAIL_DELIVERY_UNAVAILABLE(
            "email-delivery-unavailable", HttpStatus.valueOf(422), "Email delivery is not configured"),

    /**
     * The request depends on an email address the user has not proved, and it is refused until they
     * have. Two requests raise it.
     *
     * <p><b>A password reset for a user who can already sign in.</b> A reset link hands the account
     * to whoever reads that inbox. An address the user has not proved is, by definition, one nobody
     * has shown they own — it may be a typo, or the address an administrator set a minute ago with
     * {@code PATCH …/identity}, which would make "change the address, then send a reset" a way into
     * somebody else's wallet. So once there is a login to take over, the link goes only to a
     * verified address. A user who cannot sign in yet is not refused — for them the link is their
     * invitation, sent again.
     *
     * <p><b>The account at first sign-in</b>, {@code PUT /v1/accounts/me}. An account is opened for
     * a proved address only. The realm already holds an unverified user at sign-in (gitops#21), so
     * this is the second lock, for a realm configured without that.
     *
     * <p>A 409 in both cases: the same request succeeds once the address is verified.
     */
    EMAIL_NOT_VERIFIED("email-not-verified", HttpStatus.CONFLICT, "Email address not verified"),

    /**
     * Staff may not change their own roles, disable themselves or delete themselves.
     *
     * <p>Separation of duties is the reason (ADR-006): a {@code SUPER_ADMIN} who could grant
     * themselves {@code FINANCE} could approve the very adjustments that role exists to keep apart
     * from them. Together with the check that a caller is still a {@code SUPER_ADMIN} in Keycloak, it
     * also means whoever removes one is another who still is, so the last cannot be removed.
     *
     * <p>A 403 with a code of its own rather than the platform's {@code ACCESS_DENIED}: the caller
     * holds the right role, and a dashboard should be able to say why this one target is refused.
     */
    SELF_CHANGE_FORBIDDEN("self-change-forbidden", HttpStatus.FORBIDDEN, "Not allowed on your own account"),

    /**
     * A member of staff asked for an account of their own, with {@code PUT /v1/accounts/me}.
     *
     * <p>Staff hold no money, so they have no account (ADR-006): an account would put a wallet, and
     * {@code END_USER}, in the hands of somebody whose job is to administer other people's. Staff
     * means a member of the realm group {@code staff}, or anyone holding one of the staff roles,
     * asked of Keycloak at the time of the request rather than read from the token.
     *
     * <p>A 403 with a code of its own rather than the platform's {@code ACCESS_DENIED}: the token
     * carries the scope the endpoint asks for, and an app signing a staff member in should be able
     * to say why there is no wallet for them.
     */
    STAFF_ACCOUNT_FORBIDDEN("staff-account-forbidden", HttpStatus.FORBIDDEN, "Staff have no account");

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
