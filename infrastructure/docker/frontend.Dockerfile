FROM gcr.io/distroless/nodejs24-debian13:nonroot@sha256:bb6b03d81066993293a10feda7250e8e1cc034035fe9b61cfceededa7c8bf04d

LABEL org.opencontainers.image.title="nsangusa-frontend" \
      org.opencontainers.image.description="Nsangusa frontend runtime"
USER 0
RUN ["/nodejs/bin/node", "-e", "const fs = require('node:fs'); fs.appendFileSync('/etc/passwd', 'app:x:10001:10001:Application:/app:/sbin/nologin\\n'); fs.appendFileSync('/etc/group', 'app:x:10001:\\n');"]
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0 \
    HOME=/app \
    PATH=/nodejs/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
WORKDIR /app
COPY --chown=10001:10001 standalone/ ./
COPY --chown=10001:10001 static/ ./.next/static/
COPY --chown=10001:10001 public/ ./public/
USER 10001:10001
EXPOSE 3000
STOPSIGNAL SIGTERM
ENTRYPOINT ["/nodejs/bin/node"]
CMD ["server.js"]
