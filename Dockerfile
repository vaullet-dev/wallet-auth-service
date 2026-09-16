# Kept for environments that require a Dockerfile. If yours does not, prefer
# `./mvnw spring-boot:build-image`, which builds an OCI image with Cloud Native
# Buildpacks — no base image to patch, no Dockerfile to review, and a CVE fix is a
# rebuild rather than an edit. That is this service's primary path.
#
# BUILD COMMAND — this needs a Maven settings.xml, and BuildKit to pass it:
#
#   DOCKER_BUILDKIT=1 docker build \
#     --secret id=m2settings,src=$HOME/.m2/settings.xml \
#     -t auth-service .
#
# Why: dev.vaullet:common-* resolves from GitHub Packages, which requires
# authentication even for a public artefact. A plain `docker build` cannot resolve
# it and fails at dependency resolution with an error that reads like a missing
# artefact. A secret mount is used rather than an ARG or a COPY because both of
# those persist the token in the image's layer history.

# ---------- build ----------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Dependencies resolve in their own layer, so a source-only change does not
# re-download the world. This ordering is the single biggest win in Java image
# build times.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=secret,id=m2settings,target=/root/.m2/settings.xml,required=true \
    ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN --mount=type=secret,id=m2settings,target=/root/.m2/settings.xml,required=true \
    ./mvnw -B -q clean package -DskipTests

# Split the fat jar into layers that change at different rates.
RUN java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Never run as root. A container escape should not start with UID 0.
RUN addgroup -S app && adduser -S -G app app

# Ordered least- to most-frequently changed; only the last layer is rebuilt on a
# typical code change.
COPY --from=build --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/application/ ./

USER app
EXPOSE 8080

# MaxRAMPercentage, not -Xmx: the JVM then sizes the heap from the container's
# cgroup limit, so changing the pod's memory request does not require changing
# this file.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
ENV SPRING_PROFILES_ACTIVE=prod

# The orchestrator should use /actuator/health/readiness and /actuator/health/liveness.
# This HEALTHCHECK is a fallback for a plain `docker run`.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1

# The launcher class, NOT `java -jar spring-boot-loader.jar`.
#
# `extract --layers --launcher` writes the loader's CLASS FILES to
# extracted/spring-boot-loader/org/..., which the COPY above lands at /app/org/...
# There is no spring-boot-loader.jar anywhere in that layout, so the ledger's
# ENTRYPOINT dies with "Unable to access jarfile". The jar's own manifest names the
# right entry point:
#
#   Main-Class: org.springframework.boot.loader.launch.JarLauncher
#
# (`.launch.` since Boot 3.2 — it was `org.springframework.boot.loader.JarLauncher`
# before, and most copy-pasted Dockerfiles online still say that.)
#
# Left as ENTRYPOINT with no CMD so Kubernetes can override `args` — which is how
# the migration Job runs this same image with the `migrate` profile and the
# migrator credential.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
