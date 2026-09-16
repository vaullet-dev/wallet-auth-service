package dev.vaullet.auth.account.service.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An account must be addressable by something other than the identifier we mint for it.
 *
 * <p>The same rule as {@code accounts_natural_key_ck} in {@code V1__identity.sql}, stated here so it
 * is refused with a 400 naming the fields rather than reaching the database and surfacing as an
 * integrity violation. The database keeps its copy regardless — a constraint is what makes the rule
 * true for every code path, and this one makes it *legible* for the caller who broke it.
 *
 * <p>Why the rule exists at all: a retry of {@code POST /v1/accounts} is resolved against whichever
 * natural key the caller supplied. An account created with neither would be addressable only by the
 * id in its own response, making that one call the single un-retryable request in the API.
 *
 * <p>A class-level constraint rather than two field-level ones, because it is a statement about the
 * <em>pair</em>: neither field is individually required, and no annotation on one field can see the
 * other.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = AtLeastOneNaturalKeyValidator.class)
public @interface AtLeastOneNaturalKey {

    String message() default "an account needs at least one of keycloak_sub or external_ref";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
