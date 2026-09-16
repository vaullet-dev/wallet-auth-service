/**
 * Version 1 of the HTTP surface. Bind a request, call one service method, shape a response.
 *
 * <p>The package carries the version because ADR-011 §4 gives a breaking change a {@code /v2} path
 * and a twelve-month deprecation window — which means v1 and v2 must be <em>mapped at the same
 * time, in the same application</em>. A sibling {@code api.v2} package is how that happens without
 * editing a line of working v1 code, which matters when v1 is the one carrying production traffic
 * for another year.
 *
 * <p>{@code service} and {@code dao} are deliberately not versioned. ADR-011 versions the wire, not
 * the domain — and the separate DTO types in {@code v1.dto} are what let the two move
 * independently. If a v2 needed a different {@code AccountService}, the version boundary would be in
 * the wrong place.
 *
 * <p>No business logic, no transaction, no try/catch here. Errors propagate to
 * {@code AuthApiExceptionHandler}, the only place that knows about status codes, and authorisation
 * lives on the service methods — step 2 drives the same rules from a Kafka listener, which never
 * passes through a controller.
 *
 * <p>{@link org.jspecify.annotations.NullMarked} is declared here and not inherited from the parent
 * package: Java packages do not nest for annotation purposes.
 */
@NullMarked
package dev.vaullet.auth.account.api.v1;

import org.jspecify.annotations.NullMarked;
