# syntax=docker/dockerfile:1
# ── Build stage ─────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# Module split (AUDIT P1-2): only model + server are needed for the deployable
# jar — the client module (JavaFX desktop app) is never built here, so no
# platform-classified natives enter the server image.
COPY pom.xml .
COPY model/pom.xml model/
COPY server/pom.xml server/
COPY client/pom.xml client/
RUN mvn -B -q -pl server -am dependency:go-offline || true
COPY model/src model/src
COPY server/src server/src
RUN mvn -B -DskipTests -pl server -am package

# ── Runtime stage ───────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy
RUN useradd --system --no-create-home --uid 1001 sudokupro
WORKDIR /app
COPY --from=build /build/server/target/sudokupro-server-*-exec.jar app.jar
USER sudokupro
EXPOSE 8080

# The server main is headless by design; the desktop UI lives in the separate
# client module (AUDIT P1-2) and never ships in this image.

# main() defaults spring.profiles.active=prod, so SecretsGuard requires real
# DB_PASSWORD / ADMIN_PASSWORD env vars — the container refuses to start with
# missing or well-known credentials by design.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# No curl/wget in the JRE image; bash /dev/tcp works for a liveness-style check.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD bash -c '</dev/tcp/localhost/8080' || exit 1
