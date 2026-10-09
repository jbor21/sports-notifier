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

# --- Stage 2: build the web app (Vite -> static files in /web/dist) ---
FROM node:24-alpine AS web
WORKDIR /web
RUN corepack enable
# Lockfile first, for the same reason as pom.xml: a code-only change reuses the cached install layer.
COPY web/package.json web/pnpm-lock.yaml web/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY web/ ./
# The build id lets a running tab tell it was built from older code than the server's.
ARG BUILD_ID=dev
RUN VITE_BUILD_ID=$BUILD_ID pnpm build

# --- Stage 3: the image we keep (JRE only, no Maven, no source) ---
FROM eclipse-temurin:17-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=builder /build/target/sports-notifier.jar /app/app.jar
# The gateway serves these files at / (WEB_DIR).
COPY --from=web /web/dist /app/web
USER app
# Size the heap to the container's memory limit, not the host's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
