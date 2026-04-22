# --- build stage ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -q -DskipTests package || \
    mvn -q -DskipTests package

# --- runtime stage ---
FROM eclipse-temurin:21-jre

# Drop privileges: create a dedicated, numbered non-root user so Kubernetes
# SecurityContexts with `runAsNonRoot: true` admit the image, and so a
# process-level compromise cannot write outside /app.
RUN groupadd --system --gid 1001 muxai \
 && useradd --system --uid 1001 --gid muxai --home /app --shell /sbin/nologin muxai
WORKDIR /app

COPY --from=build --chown=muxai:muxai /src/target/muxai-gateway-*.jar /app/app.jar
# Ship an example config so `docker run` Just Works; operators are expected
# to bind-mount or COPY their own over /app/config/providers.yml.
COPY --chown=muxai:muxai config/providers.yml /app/config/providers.yml

USER muxai:muxai
EXPOSE 8080

# HEALTHCHECK targets Spring Boot's actuator endpoint. `wget --spider` ships
# with busybox on temurin-jre so we don't need curl in the runtime image.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget --spider -q http://127.0.0.1:8080/actuator/health || exit 1

# Container-aware JVM flags: use up to 75% of the cgroup memory limit and
# exit on OutOfMemoryError so the orchestrator can reschedule a fresh pod.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
