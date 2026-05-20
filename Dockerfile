# syntax=docker/dockerfile:1.7
# ─────────────────────────────────────────────────────────────────────────────
# Multi-stage Docker build for the Angular + Spring Boot full-stack app.
# Final image embeds the Angular SPA inside the Spring Boot JAR so a single
# container on port 8080 serves both the API and the UI.
#
# The `# syntax=` line above enables BuildKit's `--mount=type=cache` feature,
# which gives Maven and npm a persistent cache directory across builds. First
# build is still slow; subsequent builds skip dep downloads they already have.
# ─────────────────────────────────────────────────────────────────────────────

# Stage 1: Build Angular frontend
FROM node:25-alpine AS frontend-build
WORKDIR /build
# Copy lockfile + manifest first so `npm ci` can be cached when only source changes.
COPY securecapitaapp/package*.json ./
# Persistent npm cache → second build re-uses already-fetched tarballs.
RUN --mount=type=cache,target=/root/.npm npm ci
COPY securecapitaapp/ ./
RUN npm run build

# Stage 2: Build Spring Boot JAR (Angular dist is embedded in static resources)
FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /build
# Default Maven heap is small; the JAR-assembly step needs more headroom because
# the JAR now bundles the Angular dist (Bootstrap CSS, hashed asset chunks, etc.).
ENV MAVEN_OPTS="-Xmx2g -XX:+UseG1GC"

# pom.xml-only copy lets Maven cache dependencies independent of source changes.
COPY pom.xml ./

# Pre-download dependencies into a persistent cache mount.
#   --mount=type=cache,target=/root/.m2   ← BuildKit persistent volume (survives
#                                            across builds; not part of layer cache)
#   -B                                    ← batch mode (non-interactive output)
#   -T 1C                                 ← parallelism: 1 thread per CPU core
#   -Dmaven.wagon.http.retryHandler.count=3  ← retry transient HTTP failures
#
# We deliberately do NOT use -q (quiet) so you can see download progress in
# build output — without it the step looks hung even when it's working.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -T 1C -Dmaven.wagon.http.retryHandler.count=3 \
        dependency:go-offline

COPY src/ ./src/
# Angular dist becomes Spring's static content — same-origin, no CORS needed.
COPY --from=frontend-build /build/dist/securecapitaapp/browser/ ./src/main/resources/static/

# Build the JAR. Same cache mount as above so we don't re-download anything.
# We copy the JAR out of the cached layer explicitly because `target/` lives in
# the layer FS, not the cache mount.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -T 1C -Dmaven.wagon.http.retryHandler.count=3 \
        -DskipTests -Pprod package

# Stage 3: Runtime image — JRE only, no build tools
FROM eclipse-temurin:21-jre-alpine

# wget is used by the HEALTHCHECK below to probe /actuator/health.
RUN apk add --no-cache wget \
    && addgroup -S appgroup \
    && adduser -S appuser -G appgroup

WORKDIR /app
COPY --from=backend-build /build/target/*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser

EXPOSE 8080

# MaxRAMPercentage lets the JVM respect cgroup memory limits set by Docker / K8s /
# App Service. Without it, a containerized JVM may try to claim the host's memory.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

# Spring's actuator health endpoint is the canonical "am I ready to serve?" signal.
# start-period gives the JVM time to warm up before failed probes count against the container.
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod} -jar app.jar"]
