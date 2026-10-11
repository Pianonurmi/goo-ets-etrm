# Runtime image for the assembled application. Build the jar first: ./gradlew :apps:api:bootJar
FROM eclipse-temurin:25-jre-alpine AS runtime
# Numeric ids so Kubernetes can verify runAsNonRoot (the Helm chart runs as 10001).
RUN addgroup -S -g 10001 etrm && adduser -S -u 10001 -G etrm etrm
WORKDIR /app
COPY apps/api/build/libs/api.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -Djava.security.egd=file:/dev/./urandom"
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD wget -qO- http://localhost:${ETRM_MANAGEMENT_PORT:-8080}/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
