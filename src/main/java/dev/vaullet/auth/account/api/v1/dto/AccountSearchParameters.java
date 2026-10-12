package dev.vaullet.auth.account.api.v1.dto;

import dev.vaullet.auth.account.service.EndUserQuery;
import dev.vaullet.auth.common.api.PageParameters;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * The query of {@code GET /v1/accounts}: what to look for, and which page.
 *
 * <p>{@code search} is Keycloak's free-text search; {@code email} and {@code username} are exact.
 * One kind or the other, because Keycloak ignores the exact filters when free text is present — a
 * request with both is refused rather than half-answered. Nothing at all lists every end user.
 *
 * <p>The paging fields repeat {@link PageParameters}' rather than nesting it: a bound query object
 * is flat, and the limits are the same constants.
 */
public final class AccountSearchParameters {

    private final @Nullable String search;
    private final @Nullable String email;
    private final @Nullable String username;
    private final @Nullable Integer limit;
    private final @Nullable String cursor;

    public AccountSearchParameters(
            @Nullable String search,
            @Nullable String email,
            @Nullable String username,
            @Nullable Integer limit,
            @Nullable String cursor) {
        this.search = blankToNull(search);
        this.email = blankToNull(email);
        this.username = blankToNull(username);
        this.limit = limit;
        this.cursor = cursor;
    }

    @Size(max = 255)
    @Schema(example = "jane", description = "Free text: a prefix of the username, email address or a name. "
            + "* is a wildcard. Not combinable with email or username.")
    public @Nullable String getSearch() {
        return search;
    }

    @Email
    @Size(max = 255)
    @Schema(example = "jane.doe@example.com", description = "Exact.")
    public @Nullable String getEmail() {
        return email;
    }

    @Size(max = 255)
    @Schema(example = "jane.doe", description = "Exact.")
    public @Nullable String getUsername() {
        return username;
    }

    @Min(1)
    @Max(PageParameters.MAX_LIMIT)
    @Schema(example = "50", defaultValue = "50", description = "How many accounts at most. Up to 100.")
    public @Nullable Integer getLimit() {
        return limit;
    }

    @Size(max = 64)
    @Schema(example = "djE6NTA", description = "The next_cursor of the previous page. Omit for the first page.")
    public @Nullable String getCursor() {
        return cursor;
    }

    /** Bean Validation's hook for the one rule that spans fields. Not a parameter. */
    @AssertTrue(message = "search cannot be combined with email or username")
    @Schema(hidden = true)
    public boolean isOneKindOfFilter() {
        return search == null || (email == null && username == null);
    }

    public EndUserQuery toQuery() {
        return new EndUserQuery(search, email, username);
    }

    public int size() {
        return new PageParameters(limit, cursor).size();
    }

    public int position() {
        return new PageParameters(limit, cursor).position();
    }

    /** {@code ?search=} with nothing after it means no search, not a search for nothing. */
    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
