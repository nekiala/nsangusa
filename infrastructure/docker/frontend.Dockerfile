FROM node:24.20.0-bookworm-slim@sha256:ba849c60be29959425b8734d57b8b4b7d56f98edd9504c9af091d5281095a71e

LABEL org.opencontainers.image.title="nsangusa-frontend" \
      org.opencontainers.image.description="Nsangusa frontend runtime"
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app app
WORKDIR /app
COPY --chown=app:app standalone/ ./
COPY --chown=app:app static/ ./.next/static/
COPY --chown=app:app public/ ./public/
USER 10001:10001
EXPOSE 3000
STOPSIGNAL SIGTERM
CMD ["node", "server.js"]
