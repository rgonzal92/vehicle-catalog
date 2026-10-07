# The jar is the same on every kind of processor, so it is built on the kind that builds the image,
# whichever kind the image is for.
FROM --platform=$BUILDPLATFORM amazoncorretto:25.0.4-alpine AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN ./mvnw -B -DskipTests package

FROM amazoncorretto:25.0.4-alpine
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
USER 10001:10001
EXPOSE 8080
# Stops at once where it is told it serves the live site, and starts anywhere else: a build made to
# be unhealthy on the host and nowhere before it, to see the host go back to the image before.
ENTRYPOINT ["sh", "-c", "[ \"$PUBLIC_URL\" != https://catalog.rgonz.dev ] && exec java -jar /app/app.jar"]
