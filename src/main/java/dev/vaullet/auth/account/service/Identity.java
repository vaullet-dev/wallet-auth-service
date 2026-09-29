package dev.vaullet.auth.account.service;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * An account's identity: the Keycloak user behind it, as read a moment ago.
 *
 * <p>Never stored. ADR-014 §4 makes this service a facade over Keycloak for identity, so an
 * {@code Identity} exists for the length of one request and then goes away with it — which is what
 * keeps personal data out of Vaullet's own tables in both identity modes.
 *
 * <p>{@link #toString()} names nothing personal, for the same reason.
 */
public final class Identity {

    private final String username;
    private final @Nullable String email;
    private final @Nullable String firstName;
    private final @Nullable String lastName;
    private final boolean emailVerified;
    private final boolean enabled;
    private final @Nullable Instant createdAt;

    Identity(
            String username,
            @Nullable String email,
            @Nullable String firstName,
            @Nullable String lastName,
            boolean emailVerified,
            boolean enabled,
            @Nullable Instant createdAt) {
        this.username = username;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.emailVerified = emailVerified;
        this.enabled = enabled;
        this.createdAt = createdAt;
    }

    public String getUsername() {
        return username;
    }

    public Optional<String> getEmail() {
        return Optional.ofNullable(email);
    }

    public Optional<String> getFirstName() {
        return Optional.ofNullable(firstName);
    }

    public Optional<String> getLastName() {
        return Optional.ofNullable(lastName);
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    /**
     * Keycloak's own switch. Not the fraud freeze: that is the account's {@code status}, which this
     * service owns and Keycloak cannot overrule (ADR-006).
     */
    public boolean isEnabled() {
        return enabled;
    }

    public Optional<Instant> getCreatedAt() {
        return Optional.ofNullable(createdAt);
    }

    @Override
    public String toString() {
        return "Identity[emailVerified=" + emailVerified + ", enabled=" + enabled + "]";
    }
}
