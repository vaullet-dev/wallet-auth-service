package dev.vaullet.auth.account.service;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The request to create an end user, as the service understands it: the operator's reference plus
 * the identity Keycloak will hold (ADR-014 §6, local mode).
 *
 * <p>Separate from {@link NewAccount} because the two creates answer different questions. That one
 * makes an anchor and nothing else; this one makes a Keycloak user and an anchor together, and its
 * natural keys are the operator's reference and the username.
 *
 * <p>{@link #toString()} leaves out the email address, the names and the password. The reference is
 * the operator's own identifier and the one thing a log line needs to find the request again.
 */
public final class NewEndUser {

    private final String externalRef;
    private final String username;
    private final String email;
    private final @Nullable String firstName;
    private final @Nullable String lastName;
    private final boolean emailVerified;
    private final @Nullable String temporaryPassword;

    public NewEndUser(
            String externalRef,
            String username,
            String email,
            @Nullable String firstName,
            @Nullable String lastName,
            boolean emailVerified,
            @Nullable String temporaryPassword) {
        this.externalRef = externalRef;
        this.username = username;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.emailVerified = emailVerified;
        this.temporaryPassword = temporaryPassword;
    }

    public String getExternalRef() {
        return externalRef;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public Optional<String> getFirstName() {
        return Optional.ofNullable(firstName);
    }

    public Optional<String> getLastName() {
        return Optional.ofNullable(lastName);
    }

    /** True when the operator has already verified the address, for instance during KYC. */
    public boolean isEmailVerified() {
        return emailVerified;
    }

    /** A first password the user must replace at first login. Empty means none is set here. */
    public Optional<String> getTemporaryPassword() {
        return Optional.ofNullable(temporaryPassword);
    }

    @Override
    public String toString() {
        return "NewEndUser[externalRef=" + externalRef + "]";
    }
}
