FROM node:24.20.0-bookworm-slim@sha256:ba849c60be29959425b8734d57b8b4b7d56f98edd9504c9af091d5281095a71e AS base
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1

FROM base AS dependencies
COPY package.json package-lock.json ./
RUN npm ci

FROM base AS builder
ARG NSANGUSA_API_URL=http://localhost:8080
ARG NEXT_PUBLIC_SITE_URL=http://localhost:3000
ARG NEXT_PUBLIC_API_URL=
ENV NSANGUSA_API_URL=${NSANGUSA_API_URL} \
    NEXT_PUBLIC_SITE_URL=${NEXT_PUBLIC_SITE_URL} \
    NEXT_PUBLIC_API_URL=${NEXT_PUBLIC_API_URL} \
    NEXT_PUBLIC_API_MODE=api
COPY --from=dependencies /app/node_modules ./node_modules
COPY . .
RUN mkdir -p public && npm run build

FROM gcr.io/distroless/nodejs24-debian13:nonroot@sha256:bb6b03d81066993293a10feda7250e8e1cc034035fe9b61cfceededa7c8bf04d AS runner
USER 0
RUN ["/nodejs/bin/node", "-e", "const fs = require('node:fs'); fs.appendFileSync('/etc/passwd', 'nextjs:x:10001:10001:Next.js:/app:/sbin/nologin\\n'); fs.appendFileSync('/etc/group', 'nextjs:x:10001:\\n');"]
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    HOME=/app \
    PATH=/nodejs/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
COPY --from=builder --chown=10001:10001 /app/public ./public
COPY --from=builder --chown=10001:10001 /app/.next/standalone ./
COPY --from=builder --chown=10001:10001 /app/.next/static ./.next/static
USER 10001:10001
EXPOSE 3000
ENV PORT=3000
ENV HOSTNAME=0.0.0.0
STOPSIGNAL SIGTERM
ENTRYPOINT ["/nodejs/bin/node"]
CMD ["server.js"]
