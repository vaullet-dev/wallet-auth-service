package dev.vaullet.auth.account.service;

import dev.vaullet.auth.account.service.validation.AtLeastOneNaturalKey;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The request to create an account, as the service understands it.
 *
 * <p>A command object rather than two loose parameters, so the rule that binds them has somewhere to
 * live. {@link AtLeastOneNaturalKey} is a statement about the <em>pair</em> — neither field is
 * individually required — and no annotation on a single parameter could express it.
 *
 * <p>It also survives the next caller. Step 2 builds one of these from a Keycloak authentication
 * event rather than from an HTTP body, and gets the same validation without the controller being
 * involved.
 *
 * <p>Deliberately not the API's request DTO. That one is the wire shape and carries Jackson and
 * OpenAPI concerns; this is the service's input and carries the domain rule. A controller maps one
 * to the other, which is the boundary that lets the wire format change without touching the rules.
 */
@AtLeastOneNaturalKey
public final class NewAccount {

    private final @Nullable UUID keycloakSub;
    private final @Nullable String externalRef;

    public NewAccount(@Nullable UUID keycloakSub, @Nullable String externalRef) {
        this.keycloakSub = keycloakSub;
        this.externalRef = externalRef;
    }

    /** Empty when creating an unlinked anchor — federated mode, or operator pre-provisioning. */
    public Optional<UUID> getKeycloakSub() {
        return Optional.ofNullable(keycloakSub);
    }

    /** Empty when the operator has no identifier of their own to map. */
    public Optional<String> getExternalRef() {
        return Optional.ofNullable(externalRef);
    }

    @Override
    public String toString() {
        return "NewAccount[keycloakSub=" + keycloakSub + ", externalRef=" + externalRef + "]";
    }
}
