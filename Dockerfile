# syntax=docker/dockerfile:1
# ============================================================
# Partner Event Gateway — multi-stage build
#
# Stage 1: build the fat jar with Maven and a JDK
# Stage 2: layer-extract the jar for better Docker caching
# Stage 3: minimal JRE runtime image
#
# The same image runs in all 7 modes via APP_RUNTIME_MODE.
# ============================================================

# ---- Stage 1: build ------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace

# Cache deps separately from sources for faster rebuilds on code-only changes
COPY pom.xml ./
COPY .mvn/ .mvn/
COPY mvnw mvnw.cmd ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

COPY src ./src
RUN ./mvnw -B -ntp -DskipTests package

# ---- Stage 2: extract layers --------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS extract
WORKDIR /workspace
COPY --from=build /workspace/target/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# ---- Stage 3: runtime ----------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Create non-root user
RUN groupadd -r app && useradd -r -g app -d /app app && chown -R app:app /app
USER app

# Copy layered jar contents — order matters for caching
COPY --from=extract --chown=app:app /workspace/dependencies/         ./
COPY --from=extract --chown=app:app /workspace/spring-boot-loader/   ./
COPY --from=extract --chown=app:app /workspace/snapshot-dependencies/ ./
COPY --from=extract --chown=app:app /workspace/application/           ./

# Defaults — overridden by orchestrator env
ENV APP_RUNTIME_MODE=CONSUMER_ALL \
    JAVA_TOOL_OPTIONS="-XX:+UseG1GC -XX:MaxRAMPercentage=75"

EXPOSE 8080

# Liveness/readiness use Actuator; configure probes in k8s, not here.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s \
    CMD curl --fail --silent http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
