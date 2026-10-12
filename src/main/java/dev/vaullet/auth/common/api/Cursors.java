package dev.vaullet.auth.common.api;

import dev.vaullet.auth.common.error.exception.InvalidCursorException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.jspecify.annotations.Nullable;

/**
 * The {@code cursor} of ADR-011 §8's paging contract, to and from a list position.
 *
 * <p>Opaque on purpose. Inside it is a version and an offset, base64url-encoded, and nothing
 * promises either: a client that could read an offset would start computing them, and the first
 * list to move to keyset paging (R7) would break it. The version prefix is what lets that move
 * reject the old cursors cleanly instead of misreading them.
 */
public final class Cursors {

    private static final String VERSION = "v1:";
    private static final int MAX_LENGTH = 64;

    private Cursors() {}

    public static String encode(int position) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((VERSION + position).getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * The position a cursor names; the start of the list when there is none.
     *
     * @throws InvalidCursorException for anything {@link #encode} did not produce
     */
    public static int decode(@Nullable String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return 0;
        }
        if (cursor.length() > MAX_LENGTH) {
            throw new InvalidCursorException();
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            if (!decoded.startsWith(VERSION)) {
                throw new InvalidCursorException();
            }
            int position = Integer.parseInt(decoded.substring(VERSION.length()));
            if (position < 0) {
                throw new InvalidCursorException();
            }
            return position;
        } catch (IllegalArgumentException notOurs) {
            // Base64 that does not decode, and a number that does not parse (NumberFormatException
            // is an IllegalArgumentException), are the same answer to the caller.
            throw new InvalidCursorException();
        }
    }
}
