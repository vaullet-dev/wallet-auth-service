package dev.vaullet.auth.support;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * One real Keycloak for every integration test in the JVM, with {@code keycloak/vaullet-realm.json}
 * imported.
 *
 * <p>Real rather than stubbed because the behaviour worth testing is Keycloak's own, and none of it
 * is in the API documentation: a user created without {@code enabled} is disabled, {@code realmRoles}
 * on create is ignored, an undeclared attribute is dropped, and {@code PUT} with an attribute map
 * deletes whatever it leaves out. A stub would only repeat what this codebase already assumes.
 *
 * <p>The version is production's (gitops, {@code clusters/prod/keycloak.yaml}), overridable with
 * {@code -Dvaullet.test.keycloak.image=...} for an upgrade test.
 *
 * <p>Started on first use and never stopped here; Testcontainers' reaper removes it when the JVM
 * exits. Usernames in tests are unique per test, because the realm is shared across all of them.
 */
public final class KeycloakTestContainer {

    public static final String REALM = "vaullet";
    public static final String CLIENT_ID = "wallet-auth-service";
    public static final String CLIENT_SECRET = "test-only-secret";

    private static final String IMAGE =
            System.getProperty("vaullet.test.keycloak.image", "quay.io/keycloak/keycloak:26.7.4");

    private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withExposedPorts(8080)
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("keycloak/vaullet-realm.json"),
                    "/opt/keycloak/data/import/vaullet-realm.json")
            .withCommand("start-dev", "--import-realm")
            // The realm's public endpoint answers only once the import has finished.
            .waitingFor(Wait.forHttp("/realms/" + REALM).forPort(8080).forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private KeycloakTestContainer() {}

    /** Keycloak's base URL. Dev mode serves it without the {@code /auth} path production uses. */
    public static synchronized String url() {
        if (!KEYCLOAK.isRunning()) {
            KEYCLOAK.start();
        }
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
    }
}
