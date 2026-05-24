# ================================================================
# Multi-stage Dockerfile for IVDR Spring Boot App
# Stage 1: Build with Maven
# Stage 2: Minimal JRE runtime image
# ================================================================

# ---- Build Stage ----
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# Cache Maven dependencies separately
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B 2>/dev/null || true

# Copy source and build
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -DskipTests -B

# Extract layered jar for optimal Docker caching
RUN java -Djarmode=layertools -jar target/*.jar extract --destination extracted

# ---- Runtime Stage ----
FROM eclipse-temurin:21-jre-alpine AS runtime

# Install curl for healthcheck
RUN apk add --no-cache curl

# Security: run as non-root user
RUN addgroup -S ivdr && adduser -S ivdr -G ivdr
USER ivdr

WORKDIR /app

# Copy layered application (ordered by change frequency)
COPY --from=builder /build/extracted/dependencies/ ./
COPY --from=builder /build/extracted/spring-boot-loader/ ./
COPY --from=builder /build/extracted/snapshot-dependencies/ ./
COPY --from=builder /build/extracted/application/ ./

# Expose port
EXPOSE 8080

# JVM tuning for containers + Virtual Threads (Project Loom)
ENTRYPOINT ["java", \
  "-XX:+UseZGC", \
  "-XX:+ZGenerational", \
  "-Xms256m", \
  "-Xmx512m", \
  "-XX:+UseContainerSupport", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "org.springframework.boot.loader.launch.JarLauncher"]
