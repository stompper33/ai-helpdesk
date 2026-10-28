# syntax=docker/dockerfile:1

# ---- build stage: compile with Maven (dependencies cached between builds) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
# Tests run on your machine (mvn test); the image build only packages.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q package -DskipTests && cp target/helpdesk-api-*.jar /src/app.jar

# ---- runtime stage: small JRE image, non-root user ----
FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /src/app.jar app.jar
USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
