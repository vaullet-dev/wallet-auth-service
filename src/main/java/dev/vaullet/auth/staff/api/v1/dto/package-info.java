/**
 * The wire shapes of {@code /v1/staff}. Separate types from the domain for the reason
 * {@code account.api.v1.dto} gives: these change only with a version decision (ADR-011 §4).
 *
 * <p>{@link org.jspecify.annotations.NullMarked} is declared here and not inherited from the parent
 * package: Java packages do not nest for annotation purposes.
 */
@NullMarked
package dev.vaullet.auth.staff.api.v1.dto;

import org.jspecify.annotations.NullMarked;
