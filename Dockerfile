# Runtime image only. The fat jar is built on the host first:
#
#   ./gradlew :server:buildFatJar && docker compose up --build
#
# Gradle is deliberately not run inside the image: the build also configures the Android modules
# (`api` has an Android target), which would need an Android SDK in the image.
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY server/build/libs/server.jar /app/server.jar

# Overridable at run time (`-e PORT=...`); keep EXPOSE in line when changing the default.
ENV PORT=8080
EXPOSE 8080

# The server keeps everything in memory and writes nothing, so it needs no privileges.
USER 10001

ENTRYPOINT ["java", "-jar", "/app/server.jar"]
