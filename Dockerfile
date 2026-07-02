# syntax=docker/dockerfile:1
# ── Build stage ─────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# Warm the dependency cache so source changes don't re-download the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true
COPY src ./src
RUN mvn -B -DskipTests package

# ── Runtime stage ───────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy
RUN useradd --system --no-create-home --uid 1001 sudokupro
WORKDIR /app
COPY --from=build /build/target/sudokupro-*.jar app.jar
USER sudokupro
EXPOSE 8080

# Headless server mode: containers have no display, and JavaFX toolkit init
# fails with unrecoverable Errors without one. The desktop client is launched
# separately (see AUDIT P1-2 — client/server module split pending).
ENV SUDOKUPRO_UI_ENABLED=false

# main() defaults spring.profiles.active=prod, so SecretsGuard requires real
# DB_PASSWORD / ADMIN_PASSWORD env vars — the container refuses to start with
# missing or well-known credentials by design.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# No curl/wget in the JRE image; bash /dev/tcp works for a liveness-style check.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD bash -c '</dev/tcp/localhost/8080' || exit 1
