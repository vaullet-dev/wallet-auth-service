package dev.vaullet.auth.account.dao;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.vaullet.auth.common.error.exception.IdentityRejectedException;
import dev.vaullet.auth.config.AuthProperties;
import dev.vaullet.common.error.UpstreamUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Keycloak's Admin REST API, for the end users this service provisions in local mode (ADR-014).
 *
 * <p>A DAO in the same sense as {@link AccountRepository}: it knows the wire format and the status
 * codes, and takes no decisions. Whether a 409 is a retry or a collision, what a missing user means,
 * which role an account gets — all of that is {@code EndUserService}'s, so the rules read in one
 * place and can be unit-tested without a Keycloak. Nothing HTTP-shaped leaves this class: callers get
 * {@link Optional}s, {@link UpdateOutcome}s and this package's records, and a Keycloak outage arrives
 * as {@link UpstreamUnavailableException}.
 *
 * <p><b>Four Keycloak behaviours this class exists to get right</b>, all of them silent when missed:
 *
 * <ul>
 *   <li>A user created without {@code "enabled": true} is created <em>disabled</em>. It cannot log in,
 *       and every email action on it fails with "User is disabled".
 *   <li>{@code realmRoles} in the create body is ignored. Roles are a second call, and Keycloak
 *       checks the role's id as well as its name, so the id is looked up once and cached.
 *   <li>An attribute the realm's user profile does not declare is dropped without an error. That is
 *       why {@link #requireAccountIdEditableByAdminsOnly()} exists.
 *   <li>{@code PUT /users/{id}} with an {@code attributes} map replaces every attribute, and deletes
 *       the ones left out. Updates here never send the map, which tells Keycloak to keep what it has.
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

    private static final String UPSTREAM = "keycloak";
    private static final Pattern ERROR_MESSAGE = Pattern.compile("\"errorMessage\"\\s*:\\s*\"([^\"]*)\"");

    private final RestClient admin;
    private final String realm;
    private final String clientId;
    private final Map<String, RoleRef> roles = new ConcurrentHashMap<>();
    private volatile boolean accountIdGuarded;

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
        KeycloakTokens tokens = new KeycloakTokens(base, realm, clientId, secret);
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
            @Nullable Map<String, List<String>> attributes) {

        /** The account id this service wrote at creation. Empty for a user it did not create. */
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

    /** What this service asks Keycloak to create: an end user, already carrying its account id. */
    public record NewKeycloakUser(
            String username,
            String email,
            @Nullable String firstName,
            @Nullable String lastName,
            boolean emailVerified,
            @Nullable String temporaryPassword,
            UUID accountId) {

        @Override
        public String toString() {
            return "NewKeycloakUser[accountId=" + accountId + "]";
        }
    }

    public enum UpdateOutcome {
        UPDATED,
        NOT_FOUND,
        /** Keycloak answered 409: another user already has the new email address. */
        EMAIL_TAKEN
    }

    /**
     * Create an enabled user carrying {@code account_id}.
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

    /** Grant a realm role. Granting one the user already has is a no-op, so this is safe to repeat. */
    public void grantRealmRole(UUID userId, String roleName) {
        Reply<Void> reply = send(admin.post()
                        .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(List.of(role(roleName))),
                Void.class);
        if (reply.status() != 204) {
            throw failure("POST /users/{id}/role-mappings/realm", reply);
        }
    }

    /** The user's directly assigned realm role names. Composite and default roles are not expanded. */
    List<String> realmRoles(UUID userId) {
        Reply<RoleRef[]> reply = send(
                admin.get().uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId),
                RoleRef[].class);
        if (reply.status() != 200) {
            throw failure("GET /users/{id}/role-mappings/realm", reply);
        }
        RoleRef[] found = reply.body();
        return found == null ? List.of() : Arrays.stream(found).map(RoleRef::name).toList();
    }

    /**
     * Change the email address and names. A {@code null} argument leaves that field as it is, and a
     * new email address is marked unverified.
     */
    public UpdateOutcome update(UUID id, @Nullable String email, @Nullable String firstName, @Nullable String lastName) {
        Reply<Void> reply = send(admin.put()
                        .uri("/admin/realms/{realm}/users/{id}", realm, id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(UserRepresentation.forUpdate(email, firstName, lastName)),
                Void.class);
        return switch (reply.status()) {
            case 204 -> UpdateOutcome.UPDATED;
            case 404 -> UpdateOutcome.NOT_FOUND;
            case 409 -> UpdateOutcome.EMAIL_TAKEN;
            case 400 -> throw new IdentityRejectedException(reply.error());
            default -> throw failure("PUT /users/{id}", reply);
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

    record Reply<T>(int status, @Nullable T body, @Nullable URI location, String error) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RoleRef(String id, String name) {}

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
            @Nullable List<Credential> credentials) {

        static UserRepresentation forCreate(NewKeycloakUser user) {
            String password = user.temporaryPassword();
            return new UserRepresentation(
                    user.username(),
                    user.email(),
                    user.firstName(),
                    user.lastName(),
                    true,
                    user.emailVerified(),
                    Map.of(ACCOUNT_ID, List.of(user.accountId().toString())),
                    // Temporary, so Keycloak makes the user choose their own at first login and the
                    // one that passed through this service stops working.
                    password == null ? null : List.of(new Credential("password", password, true)));
        }

        /** No {@code attributes} and no {@code enabled}: both stay exactly as Keycloak has them. */
        static UserRepresentation forUpdate(@Nullable String email, @Nullable String firstName, @Nullable String lastName) {
            return new UserRepresentation(
                    null, email, firstName, lastName, null, email == null ? null : Boolean.FALSE, null, null);
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
