FROM eclipse-temurin:25.0.4_7-jre-noble@sha256:d120abd9d8d7dec94520ce974ece62d0e4eed8576eb00bbc84e6128307ab48ef

LABEL org.opencontainers.image.title="nsangusa-backend" \
      org.opencontainers.image.description="Nsangusa backend runtime"
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app app
WORKDIR /app
ARG JAR_FILE=app.jar
COPY --chown=app:app ${JAR_FILE} /app/app.jar
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/urandom"
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
