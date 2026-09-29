package dev.vaullet.auth.account.service;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What {@code PATCH /v1/accounts/{id}/identity} asks to change. An absent field stays as it is.
 *
 * <p>No username. Keycloak can be configured to allow renaming, but the username is the natural key
 * {@code EndUserService} recognises a repeated create by; letting it move would turn a retry into a
 * second user.
 */
public final class IdentityChanges {

    private final @Nullable String email;
    private final @Nullable String firstName;
    private final @Nullable String lastName;

    public IdentityChanges(@Nullable String email, @Nullable String firstName, @Nullable String lastName) {
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
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

    @Override
    public String toString() {
        return "IdentityChanges[email=" + (email != null) + ", firstName=" + (firstName != null)
                + ", lastName=" + (lastName != null) + "]";
    }
}
