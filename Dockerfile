# Stage 1: Build Angular frontend
FROM node:25-alpine AS frontend-build
WORKDIR /build
COPY securecapitaapp/package*.json ./
RUN npm ci
COPY securecapitaapp/ ./
RUN npm run build

# Stage 2: Build Spring Boot JAR (Angular dist is embedded in static resources)
FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /build
COPY pom.xml ./
RUN mvn dependency:go-offline -B -q
COPY src/ ./src/
COPY --from=frontend-build /build/dist/securecapitaapp/browser/ ./src/main/resources/static/
RUN mvn package -DskipTests -Pprod -B -q

# Stage 3: Runtime image — JRE only, no build tools
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=backend-build /build/target/*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-Dspring.profiles.active=prod", "-jar", "app.jar"]
