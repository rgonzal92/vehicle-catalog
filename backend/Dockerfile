FROM amazoncorretto:25.0.4-alpine3.24 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN ./mvnw -B -DskipTests package

FROM amazoncorretto:25.0.4-alpine3.24
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
