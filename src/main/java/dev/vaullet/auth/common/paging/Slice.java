package dev.vaullet.auth.common.paging;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One page of a list, and where the next page starts.
 *
 * <p>A position is an offset into the list as Keycloak orders it, because an offset is the only
 * position Keycloak's Admin API accepts. Nothing outside the API layer sees it as a number on the
 * wire: {@code Cursors} turns it into the opaque cursor ADR-011 §8 promises, so that if a list
 * moves to keyset paging — ADR-014's read projection, open item R7 — no client has to change.
 */
public final class Slice<T> {

    private final List<T> items;
    private final @Nullable Integer next;

    /**
     * @param items this page, in the list's order
     * @param next where the next page starts, or {@code null} when this page is the last
     */
    public Slice(List<T> items, @Nullable Integer next) {
        this.items = List.copyOf(items);
        this.next = next;
    }

    public List<T> getItems() {
        return items;
    }

    /** Empty on the last page. */
    public Optional<Integer> getNext() {
        return Optional.ofNullable(next);
    }

    @Override
    public String toString() {
        return "Slice[items=" + items.size() + ", next=" + next + "]";
    }
}
