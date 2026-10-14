# Runtime image for the assembled application. Build the jar first: ./gradlew :apps:api:bootJar
# Ubuntu rather than Alpine: the exact allocation solver's native library (OR-Tools) needs glibc (ADR 0031).
FROM eclipse-temurin:25-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab AS runtime
LABEL org.opencontainers.image.source="https://github.com/Pianonurmi/goo-ets-etrm" \
      org.opencontainers.image.licenses="PolyForm-Noncommercial-1.0.0"
# Numeric ids so Kubernetes can verify runAsNonRoot (the Helm chart runs as 10001).
RUN groupadd --system --gid 10001 etrm && useradd --system --uid 10001 --gid etrm --no-create-home --shell /usr/sbin/nologin etrm
WORKDIR /app
COPY apps/api/build/libs/api.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -Djava.security.egd=file:/dev/./urandom"
# Bash's /dev/tcp, so the check needs no HTTP client in the image.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${ETRM_MANAGEMENT_PORT:-8080} && printf 'GET /actuator/health/liveness HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '\"UP\"' <&3"]
# Native access for OR-Tools, which unpacks its library to java.io.tmpdir: /tmp must allow exec (an emptyDir does).
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar"]
