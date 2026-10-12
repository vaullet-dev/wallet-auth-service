/**
 * Wire shapes every feature's API shares: the list body and its cursor (ADR-011 §8).
 *
 * <p>Part of the API layer by its name, so only controllers and their DTOs use it — which is the
 * point: a service hands back a {@code Slice}, and what a page looks like on the wire is decided here
 * once.
 *
 * <p>{@link org.jspecify.annotations.NullMarked} is declared here and not inherited from the parent
 * package: Java packages do not nest for annotation purposes.
 */
@NullMarked
package dev.vaullet.auth.common.api;

import org.jspecify.annotations.NullMarked;
