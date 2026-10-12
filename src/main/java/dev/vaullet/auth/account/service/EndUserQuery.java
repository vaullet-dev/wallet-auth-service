package dev.vaullet.auth.account.service;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What an end-user search asks Keycloak for: free text, or an exact email address and username, or
 * nothing at all for everyone.
 *
 * <p>Free text is Keycloak's own search — a prefix match on username, email address and names, with
 * {@code *} as a wildcard. It cannot be combined with the exact filters, because Keycloak ignores
 * them when it is present; the request type refuses the combination before it gets here.
 */
public final class EndUserQuery {

    private final @Nullable String text;
    private final @Nullable String email;
    private final @Nullable String username;

    public EndUserQuery(@Nullable String text, @Nullable String email, @Nullable String username) {
        this.text = text;
        this.email = email;
        this.username = username;
    }

    public Optional<String> getText() {
        return Optional.ofNullable(text);
    }

    public Optional<String> getEmail() {
        return Optional.ofNullable(email);
    }

    public Optional<String> getUsername() {
        return Optional.ofNullable(username);
    }

    /** Which filters are set, never their values: a search for an email address is personal data. */
    @Override
    public String toString() {
        return "EndUserQuery[text=" + (text != null) + ", email=" + (email != null)
                + ", username=" + (username != null) + "]";
    }
}
