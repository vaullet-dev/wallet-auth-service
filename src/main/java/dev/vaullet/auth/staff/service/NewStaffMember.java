package dev.vaullet.auth.staff.service;

import java.util.EnumSet;
import java.util.Set;

/** What creating a staff member asks for. Built by the controller from its request type. */
public final class NewStaffMember {

    private final String username;
    private final String email;
    private final String firstName;
    private final String lastName;
    private final Set<StaffRole> roles;
    private final String temporaryPassword;

    public NewStaffMember(
            String username,
            String email,
            String firstName,
            String lastName,
            Set<StaffRole> roles,
            String temporaryPassword) {
        this.username = username;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.roles = roles.isEmpty() ? EnumSet.noneOf(StaffRole.class) : EnumSet.copyOf(roles);
        this.temporaryPassword = temporaryPassword;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public Set<StaffRole> getRoles() {
        return EnumSet.copyOf(roles);
    }

    /** Set as temporary: Keycloak makes them replace it at their first sign-in. */
    public String getTemporaryPassword() {
        return temporaryPassword;
    }

    /** The username and the roles. Never the password, and nothing else personal. */
    @Override
    public String toString() {
        return "NewStaffMember[username=" + username + ", roles=" + roles + "]";
    }
}
