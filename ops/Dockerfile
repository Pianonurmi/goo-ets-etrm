# Runtime image for the assembled application. Build the jar first: ./gradlew :apps:api:bootJar
FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S etrm && adduser -S etrm -G etrm
WORKDIR /app
COPY apps/api/build/libs/api.jar /app/app.jar
USER etrm
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -Djava.security.egd=file:/dev/./urandom"
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
