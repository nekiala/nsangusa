#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
export POSTGRES_DB=news POSTGRES_USER=news POSTGRES_PASSWORD=news
export S3_ACCESS_KEY=local-access-key S3_SECRET_KEY=local-secret-key
export ACCEPTANCE_FRONTEND_PORT="${ACCEPTANCE_FRONTEND_PORT:-13000}"
export ACCEPTANCE_BACKEND_PORT="${ACCEPTANCE_BACKEND_PORT:-18080}"
export ACCEPTANCE_MANAGEMENT_PORT="${ACCEPTANCE_MANAGEMENT_PORT:-18081}"
export ACCEPTANCE_POSTGRES_PORT="${ACCEPTANCE_POSTGRES_PORT:-15432}"
export ACCEPTANCE_S3_PORT="${ACCEPTANCE_S3_PORT:-19000}"
export ACCEPTANCE_SMTP_PORT="${ACCEPTANCE_SMTP_PORT:-11025}"
export ACCEPTANCE_MAILPIT_PORT="${ACCEPTANCE_MAILPIT_PORT:-18026}"
seen_ports=" "
for port in "$ACCEPTANCE_FRONTEND_PORT" "$ACCEPTANCE_BACKEND_PORT" "$ACCEPTANCE_MANAGEMENT_PORT" \
  "$ACCEPTANCE_POSTGRES_PORT" "$ACCEPTANCE_S3_PORT" "$ACCEPTANCE_SMTP_PORT" "$ACCEPTANCE_MAILPIT_PORT"; do
  [[ "$port" =~ ^[1-9][0-9]{0,4}$ ]] && (( port <= 65535 )) || {
    printf 'Acceptance ports must be decimal integers between 1 and 65535\n' >&2
    exit 1
  }
  [[ "$seen_ports" != *" $port "* ]] || {
    printf 'Acceptance service ports must be distinct\n' >&2
    exit 1
  }
  seen_ports="$seen_ports$port "
done
export AI_LIVE_ENABLED=false
export AI_MODEL=gpt-5-mini AI_AUTHORIZED_MODELS=gpt-5-mini,gpt-5-mini-2025-08-07
export AI_TIMEOUT=20s AI_MAX_OUTPUT_TOKENS=4096 AI_DAILY_TOKEN_BUDGET=1000000
AI_CREDENTIAL_MASTER_KEY="$(node -e 'process.stdout.write(require("node:crypto").randomBytes(32).toString("base64"))')"
export AI_CREDENTIAL_MASTER_KEY
export ACCEPTANCE_RUN_ID="${GITHUB_RUN_ID:-local}-$(date +%s)-$$"
[[ "$ACCEPTANCE_RUN_ID" =~ ^[a-z0-9][a-z0-9-]{1,70}$ ]] || {
  printf 'Invalid acceptance run identity\n' >&2
  exit 1
}
project="nsangusa-acceptance-$ACCEPTANCE_RUN_ID"
artifacts="$root/.github/artifacts/fullstack/$ACCEPTANCE_RUN_ID"
mkdir -p "$artifacts"
compose=(docker compose -p "$project"
  -f infrastructure/compose/compose.yaml
  -f infrastructure/compose/preview.yaml
  -f infrastructure/compose/acceptance.yaml)

docker info >/dev/null
"${compose[@]}" config --quiet

cleanup() {
  result=$?
  trap - EXIT
  if ! "${compose[@]}" logs --no-color > "$artifacts/services.log" 2>&1; then
    printf 'Unable to collect acceptance service logs\n' >&2
  fi
  if ! "${compose[@]}" down --volumes --timeout 30; then
    printf 'Acceptance cleanup failed for %s\n' "$project" >&2
    result=1
  fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

(cd backend && ./gradlew bootJar --no-daemon --console=plain)
"${compose[@]}" up -d --build --wait --wait-timeout 300
"${compose[@]}" ps --all --format json > "$artifacts/services.json"

export FULLSTACK_START_FRONTEND=false
export FULLSTACK_ISOLATED_ACCEPTANCE=true
export FULLSTACK_API_URL="http://127.0.0.1:$ACCEPTANCE_BACKEND_PORT"
export FULLSTACK_MANAGEMENT_URL="http://127.0.0.1:$ACCEPTANCE_MANAGEMENT_PORT"
export FULLSTACK_FRONTEND_URL="http://127.0.0.1:$ACCEPTANCE_FRONTEND_PORT"
export FULLSTACK_MAILPIT_URL="http://127.0.0.1:$ACCEPTANCE_MAILPIT_PORT"
export FULLSTACK_EDITOR_EMAIL=admin@example.test
export FULLSTACK_EDITOR_PASSWORD=administrator-demo-password
export PLAYWRIGHT_JSON_OUTPUT_NAME="$artifacts/playwright.json"

curl --fail --silent --show-error --output /dev/null "$FULLSTACK_FRONTEND_URL/sign-in"
curl --fail --silent --show-error \
  --user "$FULLSTACK_EDITOR_EMAIL:$FULLSTACK_EDITOR_PASSWORD" \
  "$FULLSTACK_FRONTEND_URL/api/v1/admin/x-accounts/capabilities" |
  node -e 'let body = ""; process.stdin.on("data", chunk => body += chunk); process.stdin.on("end", () => { if (JSON.parse(body).simulationEnabled !== true) throw new Error("Acceptance requires simulated external providers"); });'

export MEDIA_MIGRATION_JDBC_URL="jdbc:postgresql://127.0.0.1:$ACCEPTANCE_POSTGRES_PORT/news"
export DATABASE_USERNAME="${POSTGRES_USER:-news}"
export DATABASE_PASSWORD="${POSTGRES_PASSWORD:-news}"
export S3_SMOKE_ENDPOINT="http://127.0.0.1:$ACCEPTANCE_S3_PORT"
export S3_BUCKET=news-media
export MAILPIT_SMTP_HOST=127.0.0.1
export MAILPIT_SMTP_PORT="$ACCEPTANCE_SMTP_PORT"
export MAILPIT_API_BASE_URL="$FULLSTACK_MAILPIT_URL"
(cd backend && ./gradlew test --rerun \
  --tests '*MediaDeliveryMigrationTests' \
  --tests '*S3MediaPersistenceTests' \
  --tests '*MailpitNewsletterDeliveryTests' --no-daemon --console=plain)
node .github/scripts/assert-junit.mjs backend/build/test-results/test live
(cd frontend && npm run test:e2e:fullstack -- --reporter=line,json "$@")
node .github/scripts/assert-playwright.mjs "$artifacts/playwright.json"

# Only this disposable stack is interrupted; liveness must not depend on Redis.
test "$(curl --silent --show-error --max-time 15 --output /dev/null --write-out '%{http_code}' \
  "$FULLSTACK_API_URL/actuator/prometheus")" = 401
test "$(curl --silent --show-error --max-time 15 --output /dev/null --write-out '%{http_code}' \
  --header 'X-Forwarded-Port: 8081' "$FULLSTACK_API_URL/actuator/prometheus")" = 401
curl --fail --silent --show-error --max-time 15 \
  "$FULLSTACK_MANAGEMENT_URL/actuator/prometheus" > "$artifacts/prometheus.txt"
test -s "$artifacts/prometheus.txt"
"${compose[@]}" stop redis
curl --fail --silent --show-error --max-time 15 \
  "$FULLSTACK_MANAGEMENT_URL/actuator/prometheus" > "$artifacts/redis-outage-prometheus.txt"
curl --fail --silent --show-error --max-time 15 \
  "$FULLSTACK_MANAGEMENT_URL/actuator/health/liveness" > "$artifacts/redis-outage-liveness.json"
test "$(curl --silent --show-error --max-time 20 --output "$artifacts/redis-outage-readiness.json" --write-out '%{http_code}' \
  "$FULLSTACK_MANAGEMENT_URL/actuator/health/readiness")" = 503
test "$(curl --silent --show-error --max-time 15 --output /dev/null --write-out '%{http_code}' \
  "$FULLSTACK_API_URL/api/v1/articles?limit=1")" = 503
"${compose[@]}" up -d --no-deps --wait --wait-timeout 60 redis
curl --fail --silent --show-error --retry 10 --retry-all-errors --retry-delay 1 --max-time 15 \
  "$FULLSTACK_MANAGEMENT_URL/actuator/health/readiness" > "$artifacts/redis-recovered-readiness.json"
