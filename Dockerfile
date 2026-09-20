FROM gradle:8.14-jdk21 AS build
WORKDIR /src
COPY build.gradle.kts settings.gradle.kts ./
RUN gradle dependencies --no-daemon -q || true
COPY src ./src
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
COPY --from=build /src/build/libs/*.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
