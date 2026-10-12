package dev.vaullet.auth.staff.service;

import java.util.Arrays;
import java.util.Optional;

/**
 * The realm roles a staff member can hold: ADR-006's role table without {@code END_USER}.
 *
 * <p>An enum, so the set is closed where it is published. OpenAPI lists the values, and a request
 * naming any other role — {@code END_USER} included — is refused at deserialisation with a 400
 * before any rule has to consider it. The constant names are the realm role names, which is what lets
 * {@link #name()} go to Keycloak unchanged.
 */
public enum StaffRole {
    SUPPORT_AGENT,
    FRAUD_REVIEWER,
    FINANCE,
    CONFIG_ADMIN,
    SUPER_ADMIN;

    /** The staff role a realm role name stands for; empty for every other realm role. */
    static Optional<StaffRole> fromRealmRole(String name) {
        return Arrays.stream(values()).filter(role -> role.name().equals(name)).findFirst();
    }
}
