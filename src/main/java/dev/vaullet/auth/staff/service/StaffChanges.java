package dev.vaullet.auth.staff.service;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** A partial change to a staff member. An empty field stays as it is. */
public final class StaffChanges {

    private final @Nullable String email;
    private final @Nullable String firstName;
    private final @Nullable String lastName;
    private final @Nullable Boolean enabled;

    public StaffChanges(
            @Nullable String email, @Nullable String firstName, @Nullable String lastName, @Nullable Boolean enabled) {
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.enabled = enabled;
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

    public Optional<Boolean> getEnabled() {
        return Optional.ofNullable(enabled);
    }

    @Override
    public String toString() {
        return "StaffChanges[email=" + (email != null) + ", firstName=" + (firstName != null)
                + ", lastName=" + (lastName != null) + ", enabled=" + enabled + "]";
    }
}
