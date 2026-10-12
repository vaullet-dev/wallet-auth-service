/**
 * Staff management rules: who is staff, what they can be given, and what nobody may do to
 * themselves.
 *
 * <p>Nothing here knows about HTTP, for the reason {@code account.service} gives. The Keycloak
 * Admin API client is {@code account.dao}'s, shared rather than duplicated: ADR-014 allows one
 * writer to the realm.
 *
 * <p>{@link org.jspecify.annotations.NullMarked} is declared here and not inherited from the parent
 * package: Java packages do not nest for annotation purposes.
 */
@NullMarked
package dev.vaullet.auth.staff.service;

import org.jspecify.annotations.NullMarked;
