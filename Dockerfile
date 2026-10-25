# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Resolve dependencies in their own layer so code changes do not re-download them.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon installDist -x test

# glibc-based image: ONNX Runtime ships native libraries that do not run on musl (Alpine).
FROM eclipse-temurin:21-jre
RUN groupadd --system risk && useradd --system --gid risk risk
WORKDIR /app
COPY --from=build /src/build/install/fraud-risk-service /app
USER risk
ENV PORT=8081 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ZGenerational"
EXPOSE 8081
ENTRYPOINT ["/app/bin/fraud-risk-service"]
