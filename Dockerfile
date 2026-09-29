# One image for every service. SERVICE picks which one runs (see app.Launcher).

# --- Stage 1: build the JAR ---
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
# pom.xml first: dependencies are downloaded into their own cached layer,
# so a code-only change does not re-download them.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Tests need a live Redis, which a Docker build does not have. CI runs them as a separate step.
RUN mvn -B -q package -DskipTests

# --- Stage 2: the image we keep (JRE only, no Maven, no source) ---
FROM eclipse-temurin:17-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=builder /build/target/sports-notifier.jar /app/app.jar
USER app
# Size the heap to the container's memory limit, not the host's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
