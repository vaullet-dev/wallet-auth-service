/**
 * Root package of the auth service — the account anchor, and later the identity adapter.
 *
 * <p>What this service is, in one sentence: it owns Vaullet's own record of a user, and brokers
 * everything about that user's <em>identity</em> to Keycloak without keeping a copy. ADR-006 makes
 * Keycloak the only token signer in the platform; ADR-014 draws the line between the two records and
 * chose a live facade over a local projection.
 *
 * <p>{@link org.jspecify.annotations.NullMarked} makes every type in this package and all
 * sub-packages null-<em>hostile</em> by default: a reference is non-null unless explicitly marked
 * {@code @Nullable}. That earns its place here more than on most services, because the anchor has two
 * genuinely optional columns — an unlinked account has no {@code keycloak_sub}, and an account the
 * operator did not name has no {@code external_ref}. Both are real states rather than oversights, so
 * the compiler should be what remembers to handle them rather than the reviewer.
 *
 * <p>Spring Framework 7 ships JSpecify annotations throughout its own API, so an IDE or NullAway can
 * flag a possible {@code NullPointerException} at compile time instead of at 3am.
 */
@NullMarked
package dev.vaullet.auth;

import org.jspecify.annotations.NullMarked;
