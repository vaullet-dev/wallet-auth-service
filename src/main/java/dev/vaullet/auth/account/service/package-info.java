/**
 * The account rules: what a status transition means, when a repeat is a retry and when it is a
 * conflict, and where the transaction begins and ends.
 *
 * <p>Nothing here knows about HTTP. {@code LayeringTest} enforces it, and the reason is not purity:
 * step 2 drives the same rules from a Kafka listener, which has no request to bind and no response
 * to shape. A rule that can only be reached through a controller is a rule that gets reimplemented.
 *
 * <p>{@link org.jspecify.annotations.NullMarked} is declared here and not inherited from the parent
 * package: Java packages do not nest for annotation purposes.
 */
@NullMarked
package dev.vaullet.auth.account.service;

import org.jspecify.annotations.NullMarked;
