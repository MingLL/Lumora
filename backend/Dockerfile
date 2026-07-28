# syntax=docker/dockerfile:1.7

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
# Warm the dependency cache in its own layer. There is no src/ yet, so compilation
# and repackaging are skipped -- without the skips, spring-boot:repackage aborts
# the build with "Unable to find main class".
RUN mvn -q -B -DskipTests -Dmaven.main.skip=true -Dspring-boot.repackage.skip=true package
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre
RUN groupadd --system --gid 1001 lumora \
    && useradd --system --uid 1001 --gid lumora --home-dir /app --shell /usr/sbin/nologin lumora \
    && mkdir -p /app \
    && chown -R lumora:lumora /app
WORKDIR /app
COPY --from=build --chown=lumora:lumora /workspace/target/*.jar /app/lumora.jar
USER lumora
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0" \
    LUMORA_MODE=serve
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/lumora.jar"]
