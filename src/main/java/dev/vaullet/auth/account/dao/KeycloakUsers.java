package dev.vaullet.auth.account.dao;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.vaullet.auth.common.error.exception.IdentityRejectedException;
import dev.vaullet.auth.config.AuthProperties;
import dev.vaullet.common.error.exception.ResourceConflictException;
import dev.vaullet.common.error.exception.UpstreamUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Keycloak's Admin REST API, for the users this service manages in local mode: end users
 * (ADR-014) and staff.
 *
 * <p>A DAO in the same sense as {@link AccountRepository}: it knows the wire format and the status
 * codes, and takes no decisions. Whether a 409 is a retry or a collision, what a missing user means,
 * which roles a user gets — all of that belongs to {@code EndUserService} and {@code StaffService},
 * so the rules read in one place and can be unit-tested without a Keycloak. Nothing HTTP-shaped
 * leaves this class: callers get {@link Optional}s, {@link UpdateOutcome}s and this package's
 * records, and a Keycloak outage arrives as {@link UpstreamUnavailableException}.
 *
 * <p>One class for both kinds of user, and in {@code account.dao} only because end users came
 * first. ADR-014 allows one writer to the realm, and a second Admin API client for staff would be a
 * second set of credentials and a second token cache for the same writer.
 *
 * <p><b>Keycloak behaviours this class exists to get right</b>, all of them silent when missed:
 *
 * <ul>
 *   <li>A user created without {@code "enabled": true} is created <em>disabled</em>. It cannot log in,
 *       and every email action on it fails with "User is disabled".
 *   <li>{@code realmRoles} in the create body is ignored. Roles are a second call, and Keycloak
 *       checks the role's id as well as its name, so the id is looked up once and cached.
 *   <li>{@code groups} in the create body is <em>not</em> ignored: membership is written in the same
 *       transaction as the user. That is why staff are marked by the {@link #STAFF_GROUP} group
 *       rather than by their roles — a create that stops before its roles are granted still leaves a
 *       staff member, visible to the staff API and repairable through it. A group path the realm
 *       does not have fails the whole create with a bare 500, so {@link #staffGroupId()} looks it up
 *       first.
 *   <li>An attribute the realm's user profile does not declare is dropped without an error. That is
 *       why {@link #requireAccountIdEditableByAdminsOnly()} exists.
 *   <li>{@code PUT /users/{id}} with an {@code attributes} map replaces every attribute, and deletes
 *       the ones left out. Updates here never send the map, which tells Keycloak to keep what it has.
 *   <li>With no SMTP server configured, every email action fails with a 500 — "Invalid sender
 *       address 'null'" — that reads exactly like an outage. {@link #emailConfigured()} is how a
 *       caller tells the two apart before it writes anything.
 * </ul>
 *
 * <p>Only registered with {@code auth.provider: local}. In federated mode this service holds no
 * realm-management credentials, and a bean that could use them should not exist.
 */
@Repository
@ConditionalOnProperty(name = "auth.provider", havingValue = "local", matchIfMissing = true)
public class KeycloakUsers {

    /** The user attribute ADR-006's protocol mapper turns into the {@code account_id} claim. */
    public static final String ACCOUNT_ID = "account_id";

    /**
     * The realm group every staff member is created in. The realm's own record of who is staff:
     * declared in gitops' realm configuration, and holding no roles of its own.
     */
    public static final String STAFF_GROUP = "staff";

    /** Keycloak's required action that makes a user enrol an authenticator app at their next sign-in. */
    public static final String CONFIGURE_TOTP = "CONFIGURE_TOTP";

    /** The one email action this service asks for: a link to set a new password. */
    private static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";

    /** Keycloak's own default page size on its list endpoints, and the most asked for in one call here. */
    private static final int PAGE = 100;

    private static final String UPSTREAM = "keycloak";
    private static final Pattern ERROR_MESSAGE = Pattern.compile("\"errorMessage\"\\s*:\\s*\"([^\"]*)\"");

    private final RestClient admin;
    private final String realm;
    private final String clientId;
    private final Map<String, RoleRef> roles = new ConcurrentHashMap<>();
    private volatile boolean accountIdGuarded;
    private volatile @Nullable UUID staffGroup;

    KeycloakUsers(AuthProperties properties) {
        AuthProperties.Keycloak keycloak = properties.keycloak();
        String url = required(keycloak.url(), "auth.keycloak.url");
        String secret = required(keycloak.clientSecret(), "auth.keycloak.client-secret");
        this.realm = keycloak.realm();
        this.clientId = keycloak.clientId();

        // HTTP/1.1 explicitly. The JDK client's default is HTTP/2, which over plain http means an
        // h2c upgrade attempt on every connection to a server that has no reason to want one.
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(keycloak.timeout())
                .build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(keycloak.timeout());

        // RestClient.builder(), deliberately not Boot's auto-configured builder: that one carries the
        // platform's snake_case JSON (ADR-011 §8), and Keycloak speaks camelCase.
        RestClient base = RestClient.builder()
                .requestFactory(requests)
                .baseUrl(url.endsWith("/") ? url.substring(0, url.length() - 1) : url)
                .build();
        // No scope beyond the client's defaults: the Admin API checks realm-management roles, not
        // scopes, and ledger:write has no business in this token.
        KeycloakTokens tokens = new KeycloakTokens(base, realm, clientId, secret, null);
        this.admin = base.mutate()
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(tokens.bearer());
                    return execution.execute(request, body);
                })
                .build();
    }

    /**
     * A realm user, as far as this service reads one.
     *
     * <p>{@code toString} names the id only: everything else here is personal data, and a record's
     * generated {@code toString} would put it in whichever log line printed the object.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeycloakUser(
            UUID id,
            String username,
            @Nullable String email,
            @Nullable String firstName,
            @Nullable String lastName,
            boolean emailVerified,
            boolean enabled,
            @Nullable Long createdTimestamp,
            @Nullable Map<String, List<String>> attributes,
            @Nullable List<String> requiredActions) {

        /** The account id this service wrote at creation. Empty for a user it did not create as an end user. */
        public Optional<UUID> accountId() {
            List<String> values = attributes == null ? null : attributes.get(ACCOUNT_ID);
            if (values == null || values.isEmpty()) {
                return Optional.empty();
            }
            try {
                return Optional.of(UUID.fromString(values.getFirst()));
            } catch (IllegalArgumentException notAUuid) {
                return Optional.empty();
            }
        }

        @Override
        public String toString() {
            return "KeycloakUser[id=" + id + "]";
        }
    }

    /**
     * What this service asks Keycloak to create. Built through {@link #endUser} or {@link #staff}:
     * those are the only two kinds of user it makes, and each factory is the definition of one.
     */
    public record NewKeycloakUser(
            String username,
            String email,
            @Nullable String firstName,
            @Nullable String lastName,
            boolean emailVerified,
            @Nullable String temporaryPassword,
            @Nullable UUID accountId,
            boolean staff,
            List<String> requiredActions) {

        /**
         * An end user: born carrying its account id, and with no password. The user sets their own
         * from the invitation email, so no password of theirs ever passes through this service.
         */
        public static NewKeycloakUser endUser(
                String username,
                String email,
                @Nullable String firstName,
                @Nullable String lastName,
                boolean emailVerified,
                UUID accountId) {
            return new NewKeycloakUser(
                    username, email, firstName, lastName, emailVerified, null, accountId, false, List.of());
        }

        /**
         * A staff member: in {@link #STAFF_GROUP}, with no account id, and a password Keycloak makes
         * them replace at their first sign-in.
         */
        public static NewKeycloakUser staff(
                String username,
                String email,
                String firstName,
                String lastName,
                String temporaryPassword,
                List<String> requiredActions) {
            return new NewKeycloakUser(username, email, firstName, lastName, false, temporaryPassword, null, true,
                    List.copyOf(requiredActions));
        }

        @Override
        public String toString() {
            return "NewKeycloakUser[" + (staff ? "staff" : "accountId=" + accountId) + "]";
        }
    }

    public enum UpdateOutcome {
        UPDATED,
        NOT_FOUND,
        /** Keycloak answered 409: another user already has the new email address. */
        EMAIL_TAKEN
    }

    /**
     * Create an enabled user.
     *
     * @return the new user's id, or empty if Keycloak answered 409 because the username or the email
     *     address is already taken. Which of the two, and whether the existing user is this request's
     *     own earlier attempt, is the caller's question to answer.
     * @throws IdentityRejectedException if Keycloak refused the values themselves, for instance a
     *     password that fails the realm's password policy
     */
    public Optional<UUID> create(NewKeycloakUser user) {
        Reply<Void> reply = send(admin.post()
                        .uri("/admin/realms/{realm}/users", realm)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(UserRepresentation.forCreate(user)),
                Void.class);
        return switch (reply.status()) {
            case 201 -> Optional.of(idFrom(reply.location()));
            case 409 -> Optional.empty();
            case 400 -> throw new IdentityRejectedException(reply.error());
            default -> throw failure("POST /users", reply);
        };
    }

    public Optional<KeycloakUser> findById(UUID id) {
        Reply<KeycloakUser> reply = send(
                admin.get().uri("/admin/realms/{realm}/users/{id}", realm, id), KeycloakUser.class);
        return switch (reply.status()) {
            case 200 -> Optional.ofNullable(reply.body());
            case 404 -> Optional.empty();
            default -> throw failure("GET /users/{id}", reply);
        };
    }

    /**
     * The user with exactly this username. Keycloak stores usernames in lower case, so the
     * comparison ignores case.
     */
    public Optional<KeycloakUser> findByUsername(String username) {
        Reply<KeycloakUser[]> reply = send(
                admin.get().uri("/admin/realms/{realm}/users?username={username}&exact=true", realm, username),
                KeycloakUser[].class);
        if (reply.status() != 200) {
            throw failure("GET /users?username", reply);
        }
        KeycloakUser[] found = reply.body();
        if (found == null) {
            return Optional.empty();
        }
        return Arrays.stream(found)
                .filter(user -> user.username().equalsIgnoreCase(username))
                .findFirst();
    }

    /**
     * One page of realm users, in Keycloak's order, which is by username.
     *
     * <p>{@code text} is Keycloak's own {@code search}: a prefix match on username, email address and
     * names, with {@code *} as a wildcard. {@code email} and {@code username} are exact. Keycloak
     * ignores the exact filters when {@code text} is present, so a caller sends one kind or the
     * other. Service accounts are never returned.
     */
    public List<KeycloakUser> search(
            @Nullable String text, @Nullable String email, @Nullable String username, int first, int max) {
        StringBuilder uri = new StringBuilder(
                "/admin/realms/{realm}/users?first={first}&max={max}&briefRepresentation=false");
        Map<String, Object> variables = new HashMap<>(Map.of("realm", realm, "first", first, "max", max));
        // Every value travels as a URI variable, never spliced into the template: a template is only
        // partly encoded, and a '+' in an email address would reach Keycloak as a space.
        if (text != null) {
            uri.append("&search={search}");
            variables.put("search", text);
        }
        if (email != null || username != null) {
            uri.append("&exact=true");
        }
        if (email != null) {
            uri.append("&email={email}");
            variables.put("email", email);
        }
        if (username != null) {
            uri.append("&username={username}");
            variables.put("username", username);
        }
        Reply<KeycloakUser[]> reply = send(admin.get().uri(uri.toString(), variables), KeycloakUser[].class);
        if (reply.status() != 200) {
            throw failure("GET /users", reply);
        }
        KeycloakUser[] found = reply.body();
        return found == null ? List.of() : List.of(found);
    }

    // --- Staff ----------------------------------------------------------------------------------------

    /**
     * The id of {@link #STAFF_GROUP}, looked up once.
     *
     * <p>The lookup doubles as the guard: Keycloak fails a create naming a group it does not have
     * with a bare 500, which this class would report as an outage. A realm without the group is a
     * realm whose configuration predates staff management, and that is said plainly instead.
     */
    public UUID staffGroupId() {
        UUID cached = staffGroup;
        if (cached != null) {
            return cached;
        }
        Reply<GroupRef> reply = send(
                admin.get().uri("/admin/realms/{realm}/group-by-path/{group}", realm, STAFF_GROUP), GroupRef.class);
        GroupRef group = reply.body();
        if (reply.status() == 404) {
            throw new IllegalStateException("Realm '" + realm + "' has no group '/" + STAFF_GROUP + "'. Staff are "
                    + "created in it, and it belongs to the realm configuration (gitops, apps/keycloak-config).");
        }
        if (reply.status() != 200 || group == null) {
            throw failure("GET /group-by-path/{path}", reply);
        }
        staffGroup = group.id();
        return group.id();
    }

    /** One page of the staff group's members, by username. */
    public List<KeycloakUser> staffMembers(int first, int max) {
        Reply<KeycloakUser[]> reply = send(admin.get().uri(
                        "/admin/realms/{realm}/groups/{group}/members?first={first}&max={max}&briefRepresentation=false",
                        realm, staffGroupId(), first, max),
                KeycloakUser[].class);
        if (reply.status() != 200) {
            throw failure("GET /groups/{id}/members", reply);
        }
        KeycloakUser[] found = reply.body();
        return found == null ? List.of() : List.of(found);
    }

    /** Whether the user is a member of {@link #STAFF_GROUP}. False for a user Keycloak does not have. */
    public boolean isStaff(UUID userId) {
        UUID group = staffGroupId();
        Reply<GroupRef[]> reply = send(admin.get().uri(
                        "/admin/realms/{realm}/users/{id}/groups?briefRepresentation=true&max={max}", realm, userId, PAGE),
                GroupRef[].class);
        if (reply.status() == 404) {
            return false;
        }
        GroupRef[] groups = reply.body();
        if (reply.status() != 200 || groups == null) {
            throw failure("GET /users/{id}/groups", reply);
        }
        return Arrays.stream(groups).anyMatch(candidate -> group.equals(candidate.id()));
    }

    /**
     * Everyone directly granted the realm role, as ids. Composite and group grants are not expanded,
     * which matches how this service grants roles.
     */
    public Set<UUID> realmRoleHolders(String roleName) {
        return new HashSet<>(all(first -> {
            Reply<KeycloakUser[]> reply = send(admin.get().uri(
                            "/admin/realms/{realm}/roles/{name}/users?first={first}&max={max}&briefRepresentation=true",
                            realm, roleName, first, PAGE),
                    KeycloakUser[].class);
            if (reply.status() == 404) {
                throw new IllegalStateException("Realm '" + realm + "' has no realm role " + roleName);
            }
            KeycloakUser[] page = reply.body();
            if (reply.status() != 200 || page == null) {
                throw failure("GET /roles/{name}/users", reply);
            }
            return Arrays.stream(page).map(KeycloakUser::id).toList();
        }));
    }

    // --- Roles ----------------------------------------------------------------------------------------

    /** Grant a realm role. Granting one the user already has is a no-op, so this is safe to repeat. */
    public void grantRealmRole(UUID userId, String roleName) {
        grantRealmRoles(userId, List.of(roleName));
    }

    /** Grant realm roles in one call. Roles the user already has are left as they are. */
    public void grantRealmRoles(UUID userId, Collection<String> roleNames) {
        if (roleNames.isEmpty()) {
            return;
        }
        Reply<Void> reply = send(admin.post()
                        .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(roleNames.stream().map(this::role).toList()),
                Void.class);
        if (reply.status() != 204) {
            throw failure("POST /users/{id}/role-mappings/realm", reply);
        }
    }

    /** Take realm roles away in one call. Taking one the user does not hold is a no-op. */
    public void revokeRealmRoles(UUID userId, Collection<String> roleNames) {
        if (roleNames.isEmpty()) {
            return;
        }
        Reply<Void> reply = send(admin.method(HttpMethod.DELETE)
                        .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(roleNames.stream().map(this::role).toList()),
                Void.class);
        if (reply.status() != 204) {
            throw failure("DELETE /users/{id}/role-mappings/realm", reply);
        }
    }

    /**
     * Every realm role the user holds however it reached them — directly, through a composite or
     * through a group: the roles their next token will carry. Empty for a user Keycloak does not have.
     */
    public List<String> effectiveRealmRoles(UUID userId) {
        Reply<RoleRef[]> reply = send(admin.get().uri(
                        "/admin/realms/{realm}/users/{id}/role-mappings/realm/composite?briefRepresentation=true",
                        realm, userId),
                RoleRef[].class);
        if (reply.status() == 404) {
            return List.of();
        }
        RoleRef[] found = reply.body();
        if (reply.status() != 200 || found == null) {
            throw failure("GET /users/{id}/role-mappings/realm/composite", reply);
        }
        return Arrays.stream(found).map(RoleRef::name).toList();
    }

    /**
     * The user's directly assigned realm role names. Composite and default roles are not expanded,
     * though the realm's {@code default-roles-*} composite itself is among the names.
     */
    public List<String> realmRoles(UUID userId) {
        Reply<RoleRef[]> reply = send(
                admin.get().uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId),
                RoleRef[].class);
        if (reply.status() != 200) {
            throw failure("GET /users/{id}/role-mappings/realm", reply);
        }
        RoleRef[] found = reply.body();
        return found == null ? List.of() : Arrays.stream(found).map(RoleRef::name).toList();
    }

    // --- Changes --------------------------------------------------------------------------------------

    /**
     * Change the email address, the names and whether the user may sign in. A {@code null} argument
     * leaves that field as it is, and a new email address is marked unverified.
     */
    public UpdateOutcome update(
            UUID id,
            @Nullable String email,
            @Nullable String firstName,
            @Nullable String lastName,
            @Nullable Boolean enabled) {
        Reply<Void> reply = send(admin.put()
                        .uri("/admin/realms/{realm}/users/{id}", realm, id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(UserRepresentation.forUpdate(email, firstName, lastName, enabled)),
                Void.class);
        return switch (reply.status()) {
            case 204 -> UpdateOutcome.UPDATED;
            case 404 -> UpdateOutcome.NOT_FOUND;
            case 409 -> UpdateOutcome.EMAIL_TAKEN;
            case 400 -> throw new IdentityRejectedException(reply.error());
            default -> throw failure("PUT /users/{id}", reply);
        };
    }

    /**
     * End every session the user has, so no refresh token of theirs works any more. Access tokens
     * already issued live out their lifetime; Keycloak cannot recall a signed token.
     *
     * @return true if the user exists, false if there was none to sign out
     */
    public boolean logout(UUID id) {
        Reply<Void> reply = send(admin.post().uri("/admin/realms/{realm}/users/{id}/logout", realm, id), Void.class);
        return switch (reply.status()) {
            case 204 -> true;
            case 404 -> false;
            default -> throw failure("POST /users/{id}/logout", reply);
        };
    }

    /** @return true if a user was deleted, false if there was none to delete */
    public boolean delete(UUID id) {
        Reply<Void> reply = send(admin.delete().uri("/admin/realms/{realm}/users/{id}", realm, id), Void.class);
        return switch (reply.status()) {
            case 204 -> true;
            case 404 -> false;
            default -> throw failure("DELETE /users/{id}", reply);
        };
    }

    // --- Email ----------------------------------------------------------------------------------------

    /**
     * Have Keycloak email the user a link to set a new password: the invitation for a new end user,
     * and a password reset for an existing one. The password is typed into Keycloak's own page, so it
     * never passes through this service; following the link also marks the address verified.
     *
     * <p>Synchronous: a 204 means the message was handed to the SMTP server. Call {@link
     * #emailConfigured()} first — without an SMTP server this fails as a 500 that looks like an
     * outage.
     *
     * @param lifespan how long the link stays valid
     * @return true if it was sent, false if Keycloak has no such user
     * @throws ResourceConflictException if Keycloak will not email this user — it is disabled in
     *     Keycloak, or has no email address — which is the user's state, not an outage
     */
    public boolean sendSetPasswordEmail(UUID userId, Duration lifespan) {
        Reply<Void> reply = send(admin.put()
                        .uri("/admin/realms/{realm}/users/{id}/execute-actions-email?lifespan={seconds}",
                                realm, userId, lifespan.toSeconds())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(List.of(UPDATE_PASSWORD)),
                Void.class);
        return switch (reply.status()) {
            case 204 -> true;
            case 404 -> false;
            case 400 -> throw new ResourceConflictException("Keycloak will not email this user"
                    + (reply.error().isEmpty() ? "" : ": " + reply.error()));
            default -> throw failure("PUT /users/{id}/execute-actions-email", reply);
        };
    }

    /**
     * Whether the user can sign in already: they hold a password, or an identity provider such as
     * Google is linked to them. A user who can is past needing an invitation.
     */
    public boolean canSignIn(UUID userId) {
        Reply<StoredCredential[]> credentials = send(
                admin.get().uri("/admin/realms/{realm}/users/{id}/credentials", realm, userId), StoredCredential[].class);
        if (credentials.status() == 404) {
            return false;
        }
        StoredCredential[] stored = credentials.body();
        if (credentials.status() != 200 || stored == null) {
            throw failure("GET /users/{id}/credentials", credentials);
        }
        if (Arrays.stream(stored).anyMatch(credential -> "password".equals(credential.type()))) {
            return true;
        }
        Reply<IdentityLink[]> links = send(
                admin.get().uri("/admin/realms/{realm}/users/{id}/federated-identity", realm, userId),
                IdentityLink[].class);
        IdentityLink[] linked = links.body();
        if (links.status() == 404) {
            return false;
        }
        if (links.status() != 200 || linked == null) {
            throw failure("GET /users/{id}/federated-identity", links);
        }
        return linked.length > 0;
    }

    /**
     * Whether the realm has an SMTP server to send through, so an email action can succeed.
     *
     * <p>Asked every time rather than remembered. Remembering a yes would let a realm that later lost
     * its SMTP server write a user and an account before the send failed; one extra call per invitation
     * is cheap next to that, on an API that creates users a few at a time.
     */
    public boolean emailConfigured() {
        Reply<RealmSettings> reply = send(admin.get().uri("/admin/realms/{realm}", realm), RealmSettings.class);
        RealmSettings settings = reply.body();
        if (reply.status() != 200 || settings == null) {
            throw failure("GET /admin/realms/{realm}", reply);
        }
        Map<String, String> smtp = settings.smtpServer() == null ? Map.of() : settings.smtpServer();
        return isSet(smtp.get("host")) && isSet(smtp.get("from"));
    }

    // --- Guards ---------------------------------------------------------------------------------------

    /**
     * Refuse to create users unless the realm's user profile declares {@code account_id} and lets
     * only admins edit it.
     *
     * <p>Two failures, and this check stops both. Undeclared, the attribute is dropped silently and
     * the account's tokens carry no {@code account_id}. Declared as user-editable, it is worse: a user
     * could change it in Keycloak's account console, and every service downstream keys money on that
     * claim (ADR-006). The realm configuration is gitops', so this service cannot fix it; it can
     * refuse to build on it. Checked on first use and remembered once it has passed, rather than at
     * startup, so a Keycloak that is briefly down does not keep this service from starting.
     */
    public void requireAccountIdEditableByAdminsOnly() {
        if (accountIdGuarded) {
            return;
        }
        Reply<UserProfileConfig> reply = send(
                admin.get().uri("/admin/realms/{realm}/users/profile", realm), UserProfileConfig.class);
        UserProfileConfig config = reply.body();
        if (reply.status() != 200 || config == null) {
            throw failure("GET /users/profile", reply);
        }
        List<ProfileAttribute> attributes = config.attributes() == null ? List.of() : config.attributes();
        boolean adminOnly = attributes.stream()
                .filter(attribute -> ACCOUNT_ID.equals(attribute.name()))
                .findFirst()
                .map(attribute -> attribute.permissions() != null
                        && Set.of("admin").equals(attribute.permissions().edit()))
                .orElse(false);
        if (!adminOnly) {
            throw new IllegalStateException("Refusing to create users in realm '" + realm + "': its user profile must "
                    + "declare '" + ACCOUNT_ID + "' with edit permission for admin only. Undeclared, Keycloak drops "
                    + "the attribute; user-editable, a user could change which account their tokens name.");
        }
        accountIdGuarded = true;
    }

    // ------------------------------------------------------------------------------------------------

    /**
     * A role's id, looked up by name. Cached for the life of the process: role ids only change when a
     * role is deleted and recreated, which the realm config treats as a migration.
     *
     * <p>Not {@code computeIfAbsent}: that holds a lock while the lookup, an HTTP call, runs.
     */
    private RoleRef role(String name) {
        RoleRef cached = roles.get(name);
        if (cached != null) {
            return cached;
        }
        Reply<RoleRef> reply = send(admin.get().uri("/admin/realms/{realm}/roles/{name}", realm, name), RoleRef.class);
        RoleRef found = reply.body();
        if (reply.status() == 404) {
            throw new IllegalStateException("Realm '" + realm + "' has no realm role " + name);
        }
        if (reply.status() != 200 || found == null) {
            throw failure("GET /roles/{name}", reply);
        }
        roles.putIfAbsent(name, found);
        return found;
    }

    /** Every page of a list endpoint, concatenated: fetch {@link #PAGE} at a time until a short page. */
    private static <T> List<T> all(IntFunction<List<T>> page) {
        List<T> everything = new ArrayList<>();
        for (int first = 0; ; first += PAGE) {
            List<T> next = page.apply(first);
            everything.addAll(next);
            if (next.size() < PAGE) {
                return everything;
            }
        }
    }

    /** One call, and what came back, before anything decides what it means. */
    private <T> Reply<T> send(RestClient.RequestHeadersSpec<?> request, Class<T> type) {
        try {
            return request.exchangeForRequiredValue((httpRequest, response) -> {
                int status = response.getStatusCode().value();
                if (response.getStatusCode().is2xxSuccessful()) {
                    T body = type == Void.class ? null : response.bodyTo(type);
                    return new Reply<>(status, body, response.getHeaders().getLocation(), "");
                }
                return new Reply<T>(status, null, null, errorMessage(response));
            });
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException(UPSTREAM, e);
        }
    }

    /**
     * 401 and 403 are configuration, not an outage: the client's secret or its service-account roles
     * are wrong, and retrying will not help. 5xx is Keycloak being unwell, and is the 503 a caller can
     * retry. Anything else is a response this class did not expect.
     */
    private RuntimeException failure(String call, Reply<?> reply) {
        int status = reply.status();
        String detail = reply.error().isEmpty() ? "" : ": " + reply.error();
        if (status == 401 || status == 403) {
            return new IllegalStateException("Keycloak refused " + call + " with HTTP " + status + detail
                    + ". The service account of client '" + clientId + "' needs realm-management manage-users, "
                    + "view-users and view-realm in realm '" + realm + "'.");
        }
        if (status >= 500) {
            return new UpstreamUnavailableException(UPSTREAM,
                    new IllegalStateException("HTTP " + status + " from Keycloak on " + call + detail));
        }
        return new IllegalStateException("Unexpected HTTP " + status + " from Keycloak on " + call + detail);
    }

    /** Keycloak's own reason, such as {@code invalidPasswordMinLengthMessage}. Never the whole body. */
    private static String errorMessage(ClientHttpResponse response) {
        try (InputStream in = response.getBody()) {
            String body = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
            Matcher matcher = ERROR_MESSAGE.matcher(body);
            return matcher.find() ? matcher.group(1) : "";
        } catch (IOException unreadable) {
            return "";
        }
    }

    private static UUID idFrom(@Nullable URI location) {
        if (location == null) {
            throw new IllegalStateException("Keycloak created a user but sent no Location header");
        }
        String path = location.getPath();
        return UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
    }

    private static String required(@Nullable String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must be set when auth.provider is local");
        }
        return value;
    }

    private static boolean isSet(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    record Reply<T>(int status, @Nullable T body, @Nullable URI location, String error) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RoleRef(String id, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GroupRef(UUID id, @Nullable String path) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StoredCredential(@Nullable String type) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IdentityLink(@Nullable String identityProvider) {}

    /** The one realm setting read here. The SMTP password arrives masked, and is not read either way. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RealmSettings(@Nullable Map<String, String> smtpServer) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserProfileConfig(@Nullable List<ProfileAttribute> attributes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProfileAttribute(String name, @Nullable ProfilePermissions permissions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProfilePermissions(@Nullable Set<String> view, @Nullable Set<String> edit) {}

    /**
     * The request body for create and update. Nulls are left out of the JSON, and on update that is
     * the point: an absent field is one Keycloak keeps as it is.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record UserRepresentation(
            @Nullable String username,
            @Nullable String email,
            @Nullable String firstName,
            @Nullable String lastName,
            @Nullable Boolean enabled,
            @Nullable Boolean emailVerified,
            @Nullable Map<String, List<String>> attributes,
            @Nullable List<Credential> credentials,
            @Nullable List<String> groups,
            @Nullable List<String> requiredActions) {

        static UserRepresentation forCreate(NewKeycloakUser user) {
            String password = user.temporaryPassword();
            UUID accountId = user.accountId();
            return new UserRepresentation(
                    user.username(),
                    user.email(),
                    user.firstName(),
                    user.lastName(),
                    true,
                    user.emailVerified(),
                    accountId == null ? null : Map.of(ACCOUNT_ID, List.of(accountId.toString())),
                    // Temporary, so Keycloak makes the user choose their own at first login and the
                    // one that passed through this service stops working.
                    password == null ? null : List.of(new Credential("password", password, true)),
                    user.staff() ? List.of("/" + STAFF_GROUP) : null,
                    user.requiredActions().isEmpty() ? null : user.requiredActions());
        }

        /** No {@code attributes}: they stay exactly as Keycloak has them. */
        static UserRepresentation forUpdate(
                @Nullable String email, @Nullable String firstName, @Nullable String lastName, @Nullable Boolean enabled) {
            return new UserRepresentation(
                    null, email, firstName, lastName, enabled, email == null ? null : Boolean.FALSE, null, null, null, null);
        }

        @Override
        public String toString() {
            return "UserRepresentation[fields omitted: personal data and credentials]";
        }
    }

    record Credential(String type, String value, boolean temporary) {

        @Override
        public String toString() {
            return "Credential[type=" + type + ", temporary=" + temporary + "]";
        }
    }
}
