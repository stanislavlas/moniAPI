# Stage 1: Build the JAR using standard Gradle image
FROM gradle:8.10-jdk21 AS builder
WORKDIR /app

# Copy Gradle wrapper and build files first (layer cache)
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/

# Download dependencies (cached unless build files change)
RUN ./gradlew dependencies --no-daemon || true

# Copy source and build the fat JAR
COPY src/ src/
RUN ./gradlew bootJar --no-daemon

# Stage 2: Runtime on HA base image
FROM ghcr.io/home-assistant/base:latest

# Install JRE 21
RUN apk add --no-cache openjdk21-jre-headless curl

# Copy built JAR and config
COPY --from=builder /app/build/libs/*.jar /app/app.jar
COPY application.properties /app/application.properties

# Copy entrypoint
COPY run.sh /run.sh
RUN chmod +x /run.sh

WORKDIR /app
CMD ["/run.sh"]
