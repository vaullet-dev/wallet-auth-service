/**
 * The wire shapes. Separate types from the domain on purpose: these carry Jackson and OpenAPI
 * concerns and may never change without a version decision (ADR-011 §4), whereas
 * {@code Account} and {@code NewAccount} change whenever the rules do.
 *
 * <p>Keys are {@code snake_case} on the wire, set once by the platform default rather than by
 * {@code @JsonProperty} on each field — an explicit name would bypass the naming strategy and let
 * one endpoint drift into camelCase.
 *
 * <p>{@link org.jspecify.annotations.NullMarked} is declared here and not inherited from the parent
 * package: Java packages do not nest for annotation purposes.
 */
@NullMarked
package dev.vaullet.auth.account.api.v1.dto;

import org.jspecify.annotations.NullMarked;
