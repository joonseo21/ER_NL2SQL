FROM gradle:8.14-jdk21-alpine AS build
WORKDIR /workspace/backend/pipeline
COPY backend/pipeline/build.gradle backend/pipeline/settings.gradle ./
COPY backend/pipeline/src src
COPY db/migrations /workspace/db/migrations
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/backend/pipeline/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
