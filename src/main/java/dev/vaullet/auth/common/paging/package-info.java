/**
 * Paging that both features share: what a service hands back for one page of a list.
 *
 * <p>Outside the {@code api}, {@code service} and {@code dao} layers on purpose, so either side of
 * the service boundary can name it without one layer reaching into another.
 *
 * <p>{@link org.jspecify.annotations.NullMarked} is declared here and not inherited from the parent
 * package: Java packages do not nest for annotation purposes.
 */
@NullMarked
package dev.vaullet.auth.common.paging;

import org.jspecify.annotations.NullMarked;
