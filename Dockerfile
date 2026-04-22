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
WORKDIR /app
COPY --from=build /src/target/muxai-gateway-*.jar /app/app.jar
COPY config/providers.yml /app/config/providers.yml
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
