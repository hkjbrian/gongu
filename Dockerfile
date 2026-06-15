FROM eclipse-temurin:25-jdk-alpine AS build

WORKDIR /workspace

COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle .
COPY settings.gradle .
COPY src/ src/

RUN chmod +x gradlew
RUN ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

COPY --from=build /workspace/build/libs/*.jar /app/app.jar

ENV SPRING_PROFILES_ACTIVE=perf

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
