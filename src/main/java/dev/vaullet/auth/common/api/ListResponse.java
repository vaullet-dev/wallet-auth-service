package dev.vaullet.auth.common.api;

import dev.vaullet.auth.common.paging.Slice;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The body of every list in this API, ADR-011 §8's shape: {@code {"data": [...], "next_cursor":
 * "..."}}.
 *
 * <p>{@code next_cursor} is left out on the last page — the platform's {@code non_null} inclusion —
 * so "is there more" is "is the key there". A page can hold fewer items than the limit and still
 * not be the last one; only the missing cursor says the list has ended.
 */
public final class ListResponse<T> {

    private final List<T> data;
    private final @Nullable String nextCursor;

    private ListResponse(List<T> data, @Nullable String nextCursor) {
        this.data = data;
        this.nextCursor = nextCursor;
    }

    public static <S, T> ListResponse<T> of(Slice<S> slice, Function<S, T> toResponse) {
        return new ListResponse<>(
                slice.getItems().stream().map(toResponse).toList(),
                slice.getNext().map(Cursors::encode).orElse(null));
    }

    public List<T> getData() {
        return data;
    }

    @Schema(example = "djE6NTA", description = "Send as `cursor` for the next page. Absent on the last page.")
    public @Nullable String getNextCursor() {
        return nextCursor;
    }
}
