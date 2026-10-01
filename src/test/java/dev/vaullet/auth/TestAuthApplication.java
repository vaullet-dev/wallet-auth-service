package dev.vaullet.auth;

import dev.vaullet.auth.support.KeycloakTestContainer;
import dev.vaullet.common.test.PostgresContainerConfiguration;
import org.springframework.boot.SpringApplication;

/**
 * Runs the application from the IDE with its dependencies supplied by Testcontainers.
 *
 * <p>Start this instead of {@link AuthApplication} and you get a throwaway PostgreSQL, migrated by
 * Flyway, and a throwaway Keycloak with the test realm, with no {@code docker compose up} and nothing
 * to clean up afterwards. It is the fastest path from a fresh clone to a running service, and it uses
 * the same container definitions the integration tests do — so "works on my machine" and "passes in
 * CI" mean the same thing.
 *
 * <p>Running {@link AuthApplication} directly will fail: it gets no profile, and the security chain
 * refuses to start without {@code OAUTH2_ISSUER_URI}. That is the design, not a bug.
 */
public final class TestAuthApplication {

    private TestAuthApplication() {}

    public static void main(String[] args) {
        SpringApplication.from(AuthApplication::main)
                .with(PostgresContainerConfiguration.class)
                .run(concat(args,
                        "--spring.profiles.active=local",
                        // Testcontainers owns the infrastructure here, as in the test profile. The
                        // local profile switches Compose on, and Compose would start a second
                        // PostgreSQL and a second Keycloak next to these.
                        "--spring.docker.compose.enabled=false",
                        // Local mode, the default, will not start without a Keycloak: the same
                        // container and realm the integration tests use. Arguments win over
                        // application-local.yaml, which points at Compose's instead.
                        "--auth.keycloak.url=" + KeycloakTestContainer.url(),
                        "--auth.keycloak.realm=" + KeycloakTestContainer.REALM,
                        "--auth.keycloak.client-id=" + KeycloakTestContainer.CLIENT_ID,
                        "--auth.keycloak.client-secret=" + KeycloakTestContainer.CLIENT_SECRET));
    }

    private static String[] concat(String[] args, String... extra) {
        var all = new String[args.length + extra.length];
        System.arraycopy(args, 0, all, 0, args.length);
        System.arraycopy(extra, 0, all, args.length, extra.length);
        return all;
    }
}
