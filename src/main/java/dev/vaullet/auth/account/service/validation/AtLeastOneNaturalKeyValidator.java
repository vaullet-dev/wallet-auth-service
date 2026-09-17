package dev.vaullet.auth.account.service.validation;

import dev.vaullet.auth.account.service.NewAccount;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Enforces {@link AtLeastOneNaturalKey} on a {@link NewAccount}.
 *
 * <p>Reports the violation against <em>both</em> fields rather than against the object, so the
 * {@code errors} array in the problem+json body names them and a caller can highlight the two inputs
 * it must choose between. A class-level violation with no property path tells an integrator only
 * that "something about this object" is wrong.
 */
class AtLeastOneNaturalKeyValidator implements ConstraintValidator<AtLeastOneNaturalKey, NewAccount> {

    @Override
    public boolean isValid(NewAccount command, ConstraintValidatorContext context) {
        // A null command is somebody else's constraint to reject — @NotNull says so if it matters.
        // Returning true here is the Bean Validation convention and keeps the two rules independent.
        if (command == null) {
            return true;
        }
        if (command.getKeycloakSub().isPresent() || command.getExternalRef().isPresent()) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        String message = context.getDefaultConstraintMessageTemplate();
        for (String field : new String[] {"keycloakSub", "externalRef"}) {
            context.buildConstraintViolationWithTemplate(message)
                    .addPropertyNode(field)
                    .addConstraintViolation();
        }
        return false;
    }
}
