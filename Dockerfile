#Multi-stage Dockerfile for Spring Boot + Angular application

# Stage 1: Build Angular frontend
FROM node:22-alpine AS frontend-build
WORKDIR /build
COPY tesseraapp/package*.json ./
RUN npm ci
COPY tesseraapp/ ./
RUN npm run build

# Stage 2: Build Spring Boot JAR (Angular dist is embedded in static resources)
FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /build
COPY pom.xml ./
RUN mvn dependency:go-offline -B -q
COPY src/ ./src/
COPY --from=frontend-build /build/dist/tesseraapp/browser/ ./src/main/resources/static/
RUN mvn package -DskipTests -Pprod -B

# Stage 3: Runtime image — JRE only, no build tools
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=backend-build /build/target/*.jar app.jar
RUN chown appuser:appgroup app.jar

# Import Aiven's per-project MySQL CA certificate into the JRE's default truststore, so
# MYSQL_SSL_MODE=VERIFY_IDENTITY (application-prod.yml) can actually authenticate the server, not
# just encrypt to it. This cert is PUBLIC (it verifies the server's identity; it holds no private
# key), so committing it is safe. --alias is fixed so re-running this build (a fresh image each
# time) never collides with a stale alias from a previous layer.
COPY certs/aiven-mysql-ca.pem /tmp/aiven-mysql-ca.pem
RUN keytool -importcert -noprompt -alias aiven-mysql-ca \
      -file /tmp/aiven-mysql-ca.pem \
      -keystore "$JAVA_HOME/lib/security/cacerts" \
      -storepass changeit \
    && rm /tmp/aiven-mysql-ca.pem

USER appuser
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# Default to prod profile. Override at runtime with:
#   docker run -e SPRING_ACTIVE_PROFILES=qa  ...
#   docker compose --env-file .env.qa up
# application.yml reads this via ${SPRING_ACTIVE_PROFILES:dev}.
ENV SPRING_ACTIVE_PROFILES=prod
ENTRYPOINT ["java", "-jar", "app.jar"]
