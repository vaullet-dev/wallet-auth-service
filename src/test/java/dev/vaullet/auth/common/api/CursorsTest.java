package dev.vaullet.auth.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vaullet.auth.common.error.exception.InvalidCursorException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CursorsTest {

    @Test
    @DisplayName("a cursor names the position it was made from")
    void round_trip() {
        assertThat(Cursors.decode(Cursors.encode(0))).isZero();
        assertThat(Cursors.decode(Cursors.encode(150))).isEqualTo(150);
    }

    @Test
    @DisplayName("no cursor is the start of the list")
    void absent() {
        assertThat(Cursors.decode(null)).isZero();
        assertThat(Cursors.decode("")).isZero();
    }

    @Test
    @DisplayName("is URL-safe as it stands, so a client can put it in a query string unencoded")
    void url_safe() {
        assertThat(Cursors.encode(123456789)).matches("[A-Za-z0-9_-]+");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a cursor", "%%%", "djI6NTA", "NTA"})
    @DisplayName("anything it did not issue is refused: garbage, another version, a bare number")
    void refuses_foreign_cursors(String cursor) {
        assertThatThrownBy(() -> Cursors.decode(cursor)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("a negative position is refused even in the right format")
    void negative() {
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString("v1:-5".getBytes(StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> Cursors.decode(forged)).isInstanceOf(InvalidCursorException.class);
    }
}
