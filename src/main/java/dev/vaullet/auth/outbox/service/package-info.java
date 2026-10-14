/**
 * The account-created outbox (#39): what an entry says, and the relay that delivers it.
 *
 * <p>{@link dev.vaullet.auth.outbox.service.Outbox} writes an entry inside the transaction that
 * creates the account, so the two commit together. {@link dev.vaullet.auth.outbox.service.OutboxRelay}
 * delivers it afterwards, outside any transaction, and retries until the ledger has both wallets.
 * Until there is Kafka, delivering {@code identity.account-created} means the two calls the ledger's
 * consumer would make: {@code POST /v1/wallets} for {@code REAL}, then {@code BONUS}.
 *
 * <p>Nothing here knows about HTTP, for the reason {@code account.service} gives — and the relay is
 * the case in point: when Kafka arrives it publishes instead, and only the DAO it calls changes.
 *
 * <p>{@link org.jspecify.annotations.NullMarked} is declared here and not inherited from the parent
 * package: Java packages do not nest for annotation purposes.
 */
@NullMarked
package dev.vaullet.auth.outbox.service;

import org.jspecify.annotations.NullMarked;
