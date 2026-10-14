/**
 * The outbox's persistence and the one system its relay delivers to: every statement against
 * {@code identity_schema.outbox}, and the ledger's {@code POST /v1/wallets}. Nothing outside a
 * {@code dao} package touches {@code JdbcTemplate} or an HTTP client, a rule {@code LayeringTest}
 * enforces.
 *
 * <p>{@link org.jspecify.annotations.NullMarked} is declared here and not inherited from the parent
 * package: Java packages do not nest for annotation purposes.
 */
@NullMarked
package dev.vaullet.auth.outbox.dao;

import org.jspecify.annotations.NullMarked;
