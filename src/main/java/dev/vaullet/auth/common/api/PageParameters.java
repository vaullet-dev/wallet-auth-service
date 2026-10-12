package dev.vaullet.auth.common.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * The paging half of a list request, {@code ?limit=&cursor=} (ADR-011 §8).
 *
 * <p>A bound object rather than two {@code @RequestParam}s, so a bad value fails Bean Validation on a
 * model attribute — which the platform handler answers as {@code VALIDATION_FAILED} with an
 * {@code errors} array, the same body a bad JSON field gets. Constraints on bare request parameters
 * would be answered by Spring's own handler, without the {@code code} a client branches on.
 */
public final class PageParameters {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    private final @Nullable Integer limit;
    private final @Nullable String cursor;

    public PageParameters(@Nullable Integer limit, @Nullable String cursor) {
        this.limit = limit;
        this.cursor = cursor;
    }

    @Min(1)
    @Max(MAX_LIMIT)
    @Schema(example = "50", defaultValue = "50", description = "How many items at most. Up to 100.")
    public @Nullable Integer getLimit() {
        return limit;
    }

    @Size(max = 64)
    @Schema(example = "djE6NTA", description = "The next_cursor of the previous page. Omit for the first page.")
    public @Nullable String getCursor() {
        return cursor;
    }

    /** The limit, or {@link #DEFAULT_LIMIT} when the caller sent none. */
    public int size() {
        return limit == null ? DEFAULT_LIMIT : limit;
    }

    /** Where the page starts. */
    public int position() {
        return Cursors.decode(cursor);
    }
}
