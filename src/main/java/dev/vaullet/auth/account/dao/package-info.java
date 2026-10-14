/**
 * Persistence for the account anchor. Every statement against the accounts table lives here — the
 * outbox's are in {@code outbox.dao} — and nothing outside a {@code dao} package touches
 * {@code JdbcTemplate}, a rule {@code LayeringTest} enforces.
 *
 * <p>{@link org.jspecify.annotations.NullMarked} is declared here and not inherited from the parent
 * package: Java packages do not nest for annotation purposes. It earns its place in this package
 * more than anywhere else in the service, because {@code AccountRow} has two genuinely optional
 * components and the compiler should be what remembers them.
 */
@NullMarked
package dev.vaullet.auth.account.dao;

import org.jspecify.annotations.NullMarked;
