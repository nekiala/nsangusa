#!/usr/bin/env node
// Optional real-binary candidate -> previous -> candidate qualification; never builds or pulls.
import { spawnSync } from "node:child_process";
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { existsSync, mkdirSync, readdirSync, readFileSync, realpathSync, writeFileSync } from "node:fs";
import http from "node:http";
import net from "node:net";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { crc32 } from "node:zlib";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
export const OWNER = "com.nsangusa.binary-qualification";
export const DEPENDENCIES = {
  postgres: "postgres@sha256:a02db8cac496f15b094798a38254f14d6e00741f709360e5e00bb6668ea31636",
  redis: "redis@sha256:344e3945a0b431c8ff1eecd58c5573538126bd756f02fc7e218ddf1fc2546366",
  kafka: "apache/kafka@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837",
  seaweedfs: "chrislusf/seaweedfs@sha256:4e61d15fd35994cb1e43e1e553dff106794841fd9a99ade2fc8c8bfce4d7872d",
  // Bucket creation uses the server image's curl rather than a separate client image.
  "seaweedfs-init": "chrislusf/seaweedfs@sha256:4e61d15fd35994cb1e43e1e553dff106794841fd9a99ade2fc8c8bfce4d7872d",
  mailpit: "axllent/mailpit@sha256:6abc8e633df15eaf785cfcf38bae48e66f64beecdc03121e249d0f9ec15f0707"
};
const APPLICATION_IMAGES = ["previous-backend", "previous-frontend", "candidate-backend", "candidate-frontend"];
const CORE = ["postgres", "redis", "kafka", "seaweedfs", "mailpit"];
const SERVICES = [...CORE, "kafka-init", "seaweedfs-init", "backend", "frontend"];
const VOLUMES = ["postgres", "kafka", "kafka-secrets", "kafka-config", "seaweedfs", "mailpit"];
const PROTECTED_PORTS = new Set([3000, 8080, 18025, 5432, 6379, 9092, 1025, 8025, 9000, 9001, 19001, 4317, 4318, 13133]);
const PUBLIC_SLUG = "binary-qualification-public";
const PRIVATE_SLUG = "binary-qualification-private";
const PUBLIC_HEADLINE = "Binary qualification retained public article";
const BODY = "Deterministic synthetic article retained across the actual application binaries.";
export const HTTP_MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
const HTTP_TIMEOUT_SECONDS = 15;
const HTTP_EXEC_TIMEOUT_SECONDS = 20;
export const INTERNAL_HTTP_ORIGINS = {
  frontend: "http://frontend:3000",
  backend: "http://backend:8080",
  management: "http://backend:8081",
  mailpit: "http://mailpit:8025"
};

// node:http never follows redirects. The timer covers DNS, connection and the entire response body.
export const NODE_HTTP_PROBE = String.raw`
const http = require('node:http');
const MAX_BYTES = 2097152;
const MAX_TIMEOUT_MS = 15000;
const started = Date.now();
let request;
let response;
let settled = false;
let timer = setTimeout(() => fail('timeout'), MAX_TIMEOUT_MS);
function fail(error) {
  if (settled) return;
  settled = true;
  clearTimeout(timer);
  response?.destroy();
  request?.destroy();
  process.stdout.write(JSON.stringify({ error }), () => process.exit(2));
}
let inputBytes = 0;
const inputChunks = [];
process.stdin.on('data', chunk => {
  inputBytes += chunk.length;
  if (inputBytes > 65536) return fail('invalid_request');
  inputChunks.push(chunk);
});
process.stdin.on('error', () => fail('invalid_request'));
process.stdin.on('end', () => {
  if (settled) return;
  try {
    const input = JSON.parse(Buffer.concat(inputChunks).toString('utf8'));
    const url = new URL(input.url);
    if (url.protocol !== 'http:' || url.username || url.password || url.hash ||
        !Number.isInteger(input.maxBytes) || input.maxBytes < 1 || input.maxBytes > MAX_BYTES ||
        !Number.isInteger(input.timeoutMs) || input.timeoutMs < 1 || input.timeoutMs > MAX_TIMEOUT_MS ||
        !input.headers || Array.isArray(input.headers) || typeof input.headers !== 'object') {
      return fail('invalid_request');
    }
    clearTimeout(timer);
    timer = setTimeout(() => fail('timeout'), Math.max(1, input.timeoutMs - (Date.now() - started)));
    request = http.request(url, {
      method: 'GET', headers: input.headers, agent: false, maxHeaderSize: 16384
    }, incoming => {
      response = incoming;
      const advertised = incoming.headers['content-length'];
      if (advertised !== undefined && Number(advertised) > input.maxBytes) {
        return fail('response_too_large');
      }
      const chunks = [];
      let bytes = 0;
      incoming.on('data', chunk => {
        if (settled) return;
        bytes += chunk.length;
        if (bytes > input.maxBytes) return fail('response_too_large');
        chunks.push(chunk);
      });
      incoming.on('aborted', () => fail('unavailable'));
      incoming.on('error', () => fail('unavailable'));
      incoming.on('end', () => {
        if (settled) return;
        if (!incoming.complete) return fail('unavailable');
        settled = true;
        clearTimeout(timer);
        const result = {
          status: incoming.statusCode, headers: incoming.headers,
          bodyBase64: Buffer.concat(chunks).toString('base64'), bytes
        };
        process.stdout.write(JSON.stringify(result), () => process.exit(0));
      });
    });
    request.on('error', () => fail('unavailable'));
    request.end();
  } catch (_) {
    fail('invalid_request');
  }
});
`;

export class HttpTransportUnavailable extends Error {}
export class UsageError extends Error {}
class CommandTimeout extends Error {}

function require(condition, message) {
  if (!condition) throw new Error(message);
}

const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));
const sha256 = (value) => createHash("sha256").update(value).digest("hex");
const plainObject = (value) => value !== null && typeof value === "object" && !Array.isArray(value);

// Deterministic JSON with sorted keys, so equal data always produces equal digests.
export function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (plainObject(value)) {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

export function httpTarget(transport, service, requestPath, ports) {
  require(["host", "internal"].includes(transport), "an explicit supported HTTP transport is required");
  require(Object.hasOwn(INTERNAL_HTTP_ORIGINS, service), "HTTP target is not an approved service");
  require(typeof requestPath === "string" && requestPath.startsWith("/") && !requestPath.startsWith("//")
    && requestPath.length <= 2048 && ![..."\r\n\\#"].some((character) => requestPath.includes(character)),
  "HTTP target must be a bounded service-relative path");
  const origin = transport === "internal" ? INTERNAL_HTTP_ORIGINS[service] : `http://127.0.0.1:${ports[service]}`;
  return origin + requestPath;
}

export function decodeInternalResponse(result) {
  const maxEnvelope = Math.floor((HTTP_MAX_RESPONSE_BYTES + 2) / 3) * 4 + 65536;
  require(result.stdout.length <= maxEnvelope, "internal HTTP result exceeded its envelope bound");
  let value;
  try {
    value = JSON.parse(result.stdout.toString("utf8"));
  } catch {
    throw new Error("internal HTTP probe returned no valid result");
  }
  require(plainObject(value), "internal HTTP result must be an object");
  if (result.status) {
    if (["unavailable", "timeout"].includes(value.error)) throw new HttpTransportUnavailable(`internal HTTP request ${value.error}`);
    throw new Error(`internal HTTP probe rejected the request or response: ${value.error}`);
  }
  require(!("error" in value) && Number.isInteger(value.status) && value.status >= 100 && value.status <= 599
    && Number.isInteger(value.bytes) && value.bytes >= 0 && value.bytes <= HTTP_MAX_RESPONSE_BYTES
    && plainObject(value.headers) && typeof value.bodyBase64 === "string",
  "internal HTTP result is incomplete or outside bounds");
  require(Object.values(value.headers).every((header) => typeof header === "string"
    || (Array.isArray(header) && header.every((item) => typeof item === "string"))), "invalid HTTP response headers");
  require(value.bodyBase64.length % 4 === 0 && /^[A-Za-z0-9+/]*={0,2}$/.test(value.bodyBase64),
    "internal HTTP body encoding is invalid");
  const body = Buffer.from(value.bodyBase64, "base64");
  require(body.length === value.bytes && body.length <= HTTP_MAX_RESPONSE_BYTES,
    "internal HTTP body size does not match its bounded result");
  return [value.status, value.headers, body];
}

export function immutableReference(value) {
  if (/^sha256:[a-f0-9]{64}$/.test(value)) return value;
  if (/^[a-z0-9][a-z0-9./:_-]*@sha256:[a-f0-9]{64}$/.test(value)) return value;
  throw new UsageError("Use a full local sha256 image ID or repository@sha256 digest, never a tag");
}

export function checkedProject(project) {
  require(/^nsangusa-binary-[a-f0-9]{12}$/.test(project),
    "refusing any project outside the uniquely generated binary-qualification namespace");
  return project;
}

export function checkedPort(value) {
  const text = String(value);
  const port = Number(text);
  if (!/^\d+$/.test(text) || String(port) !== text || (port !== 0 && (port < 1024 || port > 65535))) {
    throw new UsageError("port must be 0 (allocate) or an integer from 1024 to 65535");
  }
  if (PROTECTED_PORTS.has(port)) throw new UsageError("refusing a reserved preview/phase4 port");
  return port;
}

export function owned(metadata, kind, name, project) {
  checkedProject(project);
  let expected = new Set(SERVICES.map((service) => `${project}-${service}`));
  if (kind === "volume") expected = new Set(VOLUMES.map((volume) => `${project}-${volume}-data`));
  else if (kind === "network") expected = new Set([`${project}-internal`]);
  require(expected.has(name), "resource name is not in this run's explicit ownership inventory");
  const labels = kind === "container" ? metadata.Config?.Labels : metadata.Labels;
  require(labels && labels[OWNER] === project && labels["com.docker.compose.project"] === project,
    `refusing non-owned ${kind}: ${name}`);
  require((metadata.Name || "").replace(/^\/+/, "") === name, `resource inspection returned a different name: ${name}`);
}

export function assertDistinctImages(images) {
  require(new Set(APPLICATION_IMAGES.map((name) => images[name].id)).size === 4,
    "four distinct backend/frontend image IDs are required; unchanged images do not prove a transition");
}

export function assertNoLiveState(state) {
  const expected = ["credentials", "liveSettings", "liveSetups", "pendingAiRequests"];
  require(plainObject(state) && canonical(Object.keys(state).sort()) === canonical(expected)
    && Object.values(state).every((value) => value === 0),
  "live settings, credentials or in-flight AI work found; live-provider rollback is NOT qualified");
}

const TIMESTAMP = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}(?::?\d{2})?)?$/;

// Nanoseconds since the epoch; PostgreSQL timestamps carry microseconds that Date would drop.
function instant(value) {
  const match = TIMESTAMP.exec(value);
  require(match, "invalid source observation timestamp");
  const [, date, hour, minute, second = "00", fraction = "", zone] = match;
  require(zone, "source observation must have an explicit timezone");
  const offset = zone === "Z" ? "Z" : zone.length === 3 ? `${zone}:00` : zone.length === 5 ? `${zone.slice(0, 3)}:${zone.slice(3)}` : zone;
  const milliseconds = Date.parse(`${date}T${hour}:${minute}:${second}${offset}`);
  require(Number.isFinite(milliseconds), "invalid source observation timestamp");
  return BigInt(milliseconds) * 1_000_000n + BigInt(fraction.padEnd(9, "0") || "0");
}

export function snapshotProjection(data, previous = null) {
  const projected = Object.fromEntries(Object.entries(data).map(([table, rows]) => [table, rows.map((row) => ({ ...row }))]));
  const observations = {};
  for (const row of projected.source_posts) {
    const { version, last_checked_at: checked } = row;
    delete row.version;
    delete row.last_checked_at;
    require(Number.isInteger(version) && version >= 0, "invalid source observation version");
    const observed = checked === null ? null : instant(checked);
    observations[row.id] = { version, last_checked_at: checked };
    if (previous !== null) {
      require(Object.hasOwn(previous, row.id), "source identity changed across snapshots");
      const before = previous[row.id];
      const beforeTime = before.last_checked_at === null ? null : instant(before.last_checked_at);
      const unchanged = version === before.version && observed === beforeTime;
      const advanced = version > before.version && observed !== null && (beforeTime === null || observed > beforeTime);
      require(unchanged || advanced, "source observation metadata regressed or changed inconsistently");
    }
  }
  require(previous === null || canonical(Object.keys(observations).sort()) === canonical(Object.keys(previous).sort()),
    "source identity changed across snapshots");
  return [projected, observations];
}

// Flyway's checksum: CRC32 over each line's UTF-8 bytes without terminators, BOM removed.
export function flywayChecksum(file) {
  let crc = 0;
  for (const line of readFileSync(file, "utf8").replace(/^﻿/, "").split(/\r\n|\r|\n/)) {
    crc = crc32(Buffer.from(line, "utf8"), crc);
  }
  return crc < 2 ** 31 ? crc : crc - 2 ** 32;
}

function migrationFiles() {
  const directory = path.join(ROOT, "backend/src/main/resources/db/migration");
  return readdirSync(directory).filter((name) => /^V\d+__.*\.sql$/.test(name))
    .sort((left, right) => Number(left.split("__")[0].slice(1)) - Number(right.split("__")[0].slice(1)))
    .map((name) => path.join(directory, name));
}

export function composeDocument(project, images, ports, pair, flywayEnabled, httpTransport = "host") {
  checkedProject(project);
  require(["host", "internal"].includes(httpTransport), "unsupported HTTP transport");
  const labels = { [OWNER]: project };
  const service = (name, image, memory, extra = {}) => ({
    image, pull_policy: "never", container_name: `${project}-${name}`, restart: "no", labels: { ...labels },
    networks: ["isolated"], cpus: 0.5, mem_limit: memory, memswap_limit: memory, pids_limit: 192,
    tmpfs: ["/work:rw,noexec,nosuid,size=128m,mode=1777"], ...extra
  });
  const port = (host, target) => ({ host_ip: "127.0.0.1", published: String(host), target, protocol: "tcp" });
  const health = (test) => ({ test, interval: "5s", timeout: "5s", retries: 30 });
  const services = {
    postgres: service("postgres", images.postgres.id, "384m", {
      environment: { POSTGRES_DB: "news", POSTGRES_USER: "news", POSTGRES_PASSWORD: "synthetic-binary-only", TMPDIR: "/work" },
      command: ["postgres", "-c", "shared_buffers=64MB", "-c", "max_connections=20",
        "-c", "statement_timeout=15000", "-c", "lock_timeout=5000"],
      volumes: ["postgres-data:/var/lib/postgresql"],
      healthcheck: health(["CMD", "pg_isready", "-U", "news", "-d", "news"])
    }),
    redis: service("redis", images.redis.id, "96m", {
      command: ["redis-server", "--appendonly", "no", "--save", "", "--protected-mode", "no",
        "--maxmemory", "64mb", "--maxmemory-policy", "noeviction"],
      healthcheck: health(["CMD", "redis-cli", "ping"])
    }),
    kafka: service("kafka", images.kafka.id, "768m", {
      hostname: "kafka",
      volumes: ["kafka-data:/var/lib/kafka/data", "kafka-secrets-data:/etc/kafka/secrets", "kafka-config-data:/mnt/shared/config"],
      environment: {
        KAFKA_NODE_ID: "1", KAFKA_PROCESS_ROLES: "broker,controller",
        KAFKA_LISTENERS: "CONTROLLER://:9093,INTERNAL://:29092",
        KAFKA_ADVERTISED_LISTENERS: "INTERNAL://kafka:29092",
        KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: "CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT",
        KAFKA_CONTROLLER_LISTENER_NAMES: "CONTROLLER", KAFKA_INTER_BROKER_LISTENER_NAME: "INTERNAL",
        KAFKA_CONTROLLER_QUORUM_VOTERS: "1@kafka:9093", KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: "1",
        KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: "1", KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: "1",
        KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: "0", KAFKA_NUM_PARTITIONS: "3",
        KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false", KAFKA_LOG_DIRS: "/var/lib/kafka/data",
        CLUSTER_ID: "MkU3OEVBNTcwNTJENDM2Qk", KAFKA_HEAP_OPTS: "-Xms128m -Xmx256m -Djava.io.tmpdir=/work"
      },
      healthcheck: {
        ...health(["CMD-SHELL", "/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --list >/dev/null 2>&1"]),
        interval: "10s", timeout: "10s", start_period: "20s", retries: 18
      }
    }),
    seaweedfs: service("seaweedfs", images.seaweedfs.id, "256m", {
      command: ["server", "-dir=/data", "-s3", "-s3.port=9000", "-filer.disableHttp",
        "-master.telemetry=false", "-master.volumeSizeLimitMB=64", "-volume.max=32"],
      environment: { AWS_ACCESS_KEY_ID: "synthetic", AWS_SECRET_ACCESS_KEY: "synthetic-binary-only",
        GOMEMLIMIT: "192MiB", GOMAXPROCS: "2" },
      volumes: ["seaweedfs-data:/data"],
      healthcheck: health(["CMD", "curl", "-fsS", "http://127.0.0.1:9000/healthz"])
    }),
    mailpit: service("mailpit", images.mailpit.id, "64m", {
      environment: { MP_MAX_MESSAGES: "20", MP_DATABASE: "/data/mailpit.db", TMPDIR: "/work" },
      volumes: ["mailpit-data:/data"], ports: [port(ports.mailpit, 8025)],
      healthcheck: health(["CMD", "/mailpit", "readyz"])
    })
  };
  services["kafka-init"] = service("kafka-init", images.kafka.id, "256m", {
    environment: { KAFKA_HEAP_OPTS: "-Xms32m -Xmx128m -Djava.io.tmpdir=/work" },
    volumes: ["kafka-data:/var/lib/kafka/data:ro", "kafka-secrets-data:/etc/kafka/secrets:ro",
      "kafka-config-data:/mnt/shared/config:ro"],
    entrypoint: ["/bin/bash", "-ec"],
    command: [`for topic in ${KAFKA_TOPICS.join(" ")}; do ` +
      "/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 " +
      "--create --if-not-exists --topic \"$$topic\" --partitions 3 --replication-factor 1; done"]
  });
  services["seaweedfs-init"] = service("seaweedfs-init", images["seaweedfs-init"].id, "64m", {
    entrypoint: ["/bin/sh", "-ec"],
    command: ["status=\"$$(curl -sS -o /dev/null -w '%{http_code}' -X PUT " +
      "--aws-sigv4 aws:amz:us-east-1:s3 --user synthetic:synthetic-binary-only " +
      "http://seaweedfs:9000/news-media)\"; " +
      "case \"$$status\" in 200|409) ;; *) echo \"bucket creation failed: $$status\" >&2; exit 1 ;; esac"]
  });
  const publicUrl = `http://127.0.0.1:${ports.frontend}`;
  services.backend = service("backend", images[`${pair}-backend`].id, "1024m", {
    cpus: 1.0, stop_grace_period: "40s", healthcheck: { disable: true },
    environment: {
      SPRING_PROFILES_ACTIVE: "local", SPRING_DOCKER_COMPOSE_ENABLED: "false",
      SPRING_FLYWAY_ENABLED: String(flywayEnabled), LOCAL_SEED: "false",
      DATABASE_URL: "jdbc:postgresql://postgres:5432/news", DATABASE_USERNAME: "news",
      DATABASE_PASSWORD: "synthetic-binary-only", DATABASE_POOL_SIZE: "4",
      KAFKA_BOOTSTRAP_SERVERS: "kafka:29092", REDIS_HOST: "redis", MAIL_HOST: "mailpit", MAIL_PORT: "1025",
      S3_ENDPOINT: "http://seaweedfs:9000", S3_ACCESS_KEY: "synthetic",
      S3_SECRET_KEY: "synthetic-binary-only", S3_SERVER_SIDE_ENCRYPTION: "none",
      PUBLIC_BASE_URL: publicUrl, COOKIE_SECURE: "false", PROVIDER_MODE: "fake",
      AI_LIVE_ENABLED: "false", AI_API_KEY: "", IMAGE_API_KEY: "",
      AI_CREDENTIAL_MASTER_KEY: "${BINARY_MASTER_KEY:?Provided ephemerally by the runner}",
      MANAGEMENT_SERVER_PORT: "8081", NEWS_OPERATIONS_INTERNAL_METRICS_ENABLED: "true",
      MANAGEMENT_TRACING_ENABLED: "false", MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED: "false",
      OTEL_SDK_DISABLED: "true", OTEL_METRICS_EXPORTER: "none", TMPDIR: "/work",
      JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=55 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/work"
    },
    ports: [port(ports.backend, 8080), port(ports.management, 8081)]
  });
  services.frontend = service("frontend", images[`${pair}-frontend`].id, "384m", {
    stop_grace_period: "20s", healthcheck: { disable: true },
    environment: {
      NODE_ENV: "production", NEXT_TELEMETRY_DISABLED: "1", NSANGUSA_API_URL: "http://backend:8080",
      PUBLIC_BASE_URL: publicUrl, NEXT_PUBLIC_SITE_URL: publicUrl, NEXT_PUBLIC_API_MODE: "api",
      TMPDIR: "/work", NODE_OPTIONS: "--max-old-space-size=256"
    },
    ports: [port(ports.frontend, 3000)]
  });
  if (httpTransport === "internal") for (const definition of Object.values(services)) delete definition.ports;
  return {
    name: project, services,
    networks: { isolated: { name: `${project}-internal`, internal: true, labels } },
    volumes: Object.fromEntries(VOLUMES.map((name) => [`${name}-data`, { name: `${project}-${name}-data`, labels }]))
  };
}

export const KAFKA_TOPICS = ["news.ingestion.v1", "news.editorial.v1", "news.publication.v1", "news.notifications.v1"]
  .flatMap((topic) => [topic, `${topic}.retry`, `${topic}.dlt`]);

const FIXTURE_SQL = `
begin;
insert into monitored_x_accounts(id,account_id,handle,display_name,topics,relevance_threshold,
 monitoring_enabled,created_at)
values ('71000000-0000-0000-0000-000000000001','binary-synthetic','binarysynthetic',
 'Synthetic binary fixture','qualification',0.5,false,'2026-01-01Z');
insert into source_posts(id,monitored_account_id,post_id,account_id,handle,canonical_url,
 permitted_text,published_at,ingested_at,status)
values ('72000000-0000-0000-0000-000000000001','71000000-0000-0000-0000-000000000001',
 'binary-source','binary-synthetic','binarysynthetic','https://example.invalid/binary-source',
 'Synthetic source; monitoring disabled','2026-01-01Z','2026-01-01Z','active');
insert into articles(id,slug,headline,summary,body,seo_title,seo_description,topic,tags,state,
 confidence,warnings,created_at,updated_at,published_at,comments_enabled,image_approval_required)
select ('73000000-0000-0000-0000-00000000000'||n)::uuid,
 case when n=1 then 'binary-qualification-public' else 'binary-qualification-private' end,
 case when n=1 then 'Binary qualification retained public article' else 'Private fixture must not leak' end,
 'Synthetic binary qualification summary',
 'Deterministic synthetic article retained across the actual application binaries.',
 'Binary qualification','Synthetic fixture, not editorial output','qualification','qualification',
 case when n=1 then 'PUBLISHED' else 'AWAITING_REVIEW' end,0.95,'','2026-01-01Z','2026-01-01Z',
 case when n=1 then '2026-01-01Z'::timestamptz else null end,false,false
from generate_series(1,2) n;
insert into article_sources(id,article_id,source_post_id,account,post_id,url,published_at)
values ('74000000-0000-0000-0000-000000000001','73000000-0000-0000-0000-000000000001',
 '72000000-0000-0000-0000-000000000001','binarysynthetic','binary-source',
 'https://example.invalid/binary-source','2026-01-01Z');
insert into outbox_events(id,event_type,aggregate_id,correlation_id,idempotency_key,envelope_json,
 created_at,published_at)
values ('75000000-0000-0000-0000-000000000001','SyntheticAlreadyAcknowledged',
 '73000000-0000-0000-0000-000000000001','73000000-0000-0000-0000-000000000001',
 'binary-fixture-acknowledged','{"synthetic":true}','2026-01-01Z','2026-01-01Z');
insert into processed_events(event_id,consumer_name,processed_at)
values ('75000000-0000-0000-0000-000000000001','binary-fixture-consumer','2026-01-01Z');
commit;
`;

// node:http does not follow redirects or consult proxy environment variables.
function hostRequest(url, headers) {
  return new Promise((resolve, reject) => {
    const request = http.get(url, { headers, agent: false, timeout: HTTP_TIMEOUT_SECONDS * 1000 }, (response) => {
      const chunks = [];
      let bytes = 0;
      response.on("data", (chunk) => {
        bytes += chunk.length;
        if (bytes > HTTP_MAX_RESPONSE_BYTES) {
          response.destroy();
          reject(new Error("HTTP response exceeds qualification bound"));
        } else {
          chunks.push(chunk);
        }
      });
      response.on("error", (error) => reject(new HttpTransportUnavailable(error.message)));
      response.on("end", () => resolve([response.statusCode, response.headers, Buffer.concat(chunks)]));
    });
    request.on("timeout", () => request.destroy(new Error("timeout")));
    request.on("error", (error) => reject(new HttpTransportUnavailable(error.message)));
  });
}

function reservePort(port) {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once("error", reject);
    server.listen({ host: "127.0.0.1", port, exclusive: true }, () => resolve(server));
  });
}

export class Qualification {
  constructor(options, output) {
    this.options = options;
    this.output = output;
    this.project = checkedProject("nsangusa-binary-" + randomUUID().replaceAll("-", "").slice(0, 12));
    this.config = path.join(output, "compose.json");
    this.masterKey = randomBytes(32).toString("base64");
    this.environment = { ...process.env, BINARY_MASTER_KEY: this.masterKey };
    this.images = {};
    this.ports = {};
    this.reservations = {};
    this.created = false;
    this.evidence = {
      schemaVersion: 1, kind: "isolated-real-binary-rollback-forward", status: "running",
      project: this.project, phases: [], liveProviderRollbackQualified: false, productionDeployment: false,
      productionSloQualified: false, paidProviderCallsPermitted: false, httpTransport: options.httpTransport,
      publishedPortAccessQualified: false, ingressAccessQualified: false,
      fixtureScope: "pre-existing synthetic business rows; monotonic source observations allowed; " +
        "no publication mutation qualification",
      limits: {
        steadyStateMemoryMiB: 2976, initializerMemoryMiB: 256, backendHeapPercent: 55, databasePoolSize: 4,
        fixtureArticles: 2, maxHttpResponseBytes: HTTP_MAX_RESPONSE_BYTES,
        internalHttpDeadlineSeconds: HTTP_TIMEOUT_SECONDS, internalHttpExecTimeoutSeconds: HTTP_EXEC_TIMEOUT_SECONDS,
        perPhaseStartupSeconds: options.startupTimeout, internetEgress: "blocked by owned internal network"
      },
      unqualified: ["live provider credentials/settings and active requests",
        "concurrent rolling replicas and sustained traffic",
        "managed PITR/failover, TLS/ACLs and production deployment",
        "editorial mutations, broker catch-up and newsletter delivery"]
    };
    if (options.httpTransport === "internal") this.evidence.unqualified.push("published host ports and ingress access");
    this.save();
  }

  redact(text) {
    return text.replaceAll(this.masterKey, "[redacted]");
  }

  save() {
    writeFileSync(path.join(this.output, "evidence.json"), JSON.stringify(this.evidence, null, 2) + "\n");
  }

  command(args, { data, timeout = 90, check = true } = {}) {
    const result = spawnSync(args[0], args.slice(1), {
      cwd: ROOT, env: this.environment, input: data, timeout: timeout * 1000, maxBuffer: 64 * 1024 * 1024
    });
    if (result.error?.code === "ETIMEDOUT") throw new CommandTimeout(`${args.slice(0, 3).join(",")} timed out`);
    if (result.error) throw result.error;
    if (check && result.status) {
      throw new Error(`${args.slice(0, 3).join(",")} exited ${result.status}: ${this.redact(result.stderr.toString()).slice(-4000)}`);
    }
    return result;
  }

  docker(args, options) {
    return this.command(["docker", ...args], options);
  }

  compose(args, options) {
    return this.docker(["compose", "--project-name", this.project, "--file", this.config, ...args], options);
  }

  inspect(kind, name, { allowMissing = false, timeout = 90 } = {}) {
    const result = this.docker([kind, "inspect", name], { check: false, timeout });
    if (result.status) {
      const detail = result.stderr.toString().toLowerCase();
      const missing = ["no such object", "no such container", "no such volume", "no such network",
        `network ${name} not found`].some((text) => detail.includes(text));
      require(allowMissing && missing, `cannot inspect ${kind} ${name}`);
      return null;
    }
    return JSON.parse(result.stdout)[0];
  }

  resources() {
    const ordered = ["frontend", "backend", "seaweedfs-init", "kafka-init", ...[...CORE].reverse()];
    return [...ordered.map((name) => ["container", `${this.project}-${name}`]),
      ...VOLUMES.map((name) => ["volume", `${this.project}-${name}-data`]),
      ["network", `${this.project}-internal`]];
  }

  async preflight() {
    let host = process.env.DOCKER_HOST;
    if (!host) {
      const context = this.docker(["context", "show"]).stdout.toString().trim();
      host = JSON.parse(this.docker(["context", "inspect", context]).stdout)[0].Endpoints.docker.Host;
    }
    require(host.startsWith("unix://") || host.startsWith("npipe://"), "a local Docker engine is required");
    const references = { ...DEPENDENCIES, ...Object.fromEntries(APPLICATION_IMAGES.map((name) => [name, this.options[name]])) };
    const declaredVolumes = {
      postgres: ["/var/lib/postgresql"],
      kafka: ["/var/lib/kafka/data", "/etc/kafka/secrets", "/mnt/shared/config"],
      seaweedfs: ["/data"]
    };
    for (const [name, reference] of Object.entries(references)) {
      immutableReference(reference);
      const result = this.docker(["image", "inspect", reference], { check: false });
      require(result.status === 0, `immutable image must already be available locally: ${reference}`);
      const image = JSON.parse(result.stdout)[0];
      require(Object.keys(image.Config.Volumes || {}).every((volume) => (declaredVolumes[name] || []).includes(volume)),
        `image declares unmapped anonymous volumes; explicit owned mapping required: ${name}`);
      this.images[name] = { reference, id: image.Id, platform: `${image.Os}/${image.Architecture}` };
    }
    assertDistinctImages(this.images);
    for (const [kind, name] of this.resources()) {
      require(this.inspect(kind, name, { allowMissing: true }) === null, `refusing an existing resource: ${name}`);
    }
    for (const name of ["frontend", "backend", "management", "mailpit"]) {
      const reservation = await reservePort(this.options[`${name}-port`]);
      this.reservations[name] = reservation;
      const selected = reservation.address().port;
      require(!PROTECTED_PORTS.has(selected) && !Object.values(this.ports).includes(selected),
        "ports must be distinct and cannot reuse protected stacks");
      this.ports[name] = selected;
    }
    this.evidence.images = this.images;
    this.evidence.ports = this.ports;
    this.evidence.portsPurpose = this.options.httpTransport === "internal"
      ? "public-origin configuration only; no host ports published or tested"
      : "published loopback HTTP endpoints";
    this.evidence.httpEndpoints = Object.fromEntries(Object.keys(INTERNAL_HTTP_ORIGINS)
      .map((service) => [service, httpTarget(this.options.httpTransport, service, "/", this.ports)]));
    this.evidence.ownedResources = this.resources();
    this.save();
  }

  async release(name) {
    const reservation = this.reservations[name];
    delete this.reservations[name];
    if (reservation) await new Promise((resolve) => reservation.close(resolve));
  }

  async releaseAll() {
    for (const name of Object.keys(this.reservations)) await this.release(name);
  }

  writeCompose(pair, flyway) {
    const document = composeDocument(this.project, this.images, this.ports, pair, flyway, this.options.httpTransport);
    writeFileSync(this.config, JSON.stringify(document, null, 2) + "\n");
  }

  async startDependencies() {
    this.writeCompose("candidate", true);
    this.compose(["config", "--quiet"]);
    await this.release("mailpit");
    this.created = true;
    this.compose(["up", "-d", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "180", ...CORE], { timeout: 210 });
    for (const name of ["kafka-init", "seaweedfs-init"]) {
      this.compose(["run", "--rm", "--no-deps", "--pull", "never", "--name", `${this.project}-${name}`, name], { timeout: 180 });
    }
    const network = this.inspect("network", `${this.project}-internal`);
    owned(network, "network", `${this.project}-internal`, this.project);
    require(network.Internal === true, "network must prohibit external egress");
    for (const volume of VOLUMES) {
      const name = `${this.project}-${volume}-data`;
      owned(this.inspect("volume", name), "volume", name, this.project);
    }
    const database = this.inspect("container", `${this.project}-postgres`);
    owned(database, "container", `${this.project}-postgres`, this.project);
    this.databaseId = database.Id;
  }

  sql(text) {
    return this.docker(["exec", "-i", `${this.project}-postgres`, "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1",
      "-U", "news", "-d", "news"], { data: Buffer.from(text) }).stdout.toString().trim();
  }

  async request(service, requestPath, headers = {}) {
    const url = httpTarget(this.options.httpTransport, service, requestPath, this.ports);
    const accept = requestPath === "/actuator/prometheus" ? "*/*" : "application/json";
    const requestHeaders = { Accept: accept, "Accept-Encoding": "identity", ...headers };
    if (this.options.httpTransport === "internal") {
      const name = `${this.project}-frontend`;
      const metadata = this.inspect("container", name, { timeout: 5 });
      owned(metadata, "container", name, this.project);
      require(metadata.State?.Running === true, "owned frontend must be running before executing an HTTP probe");
      require(canonical(Object.keys(metadata.NetworkSettings.Networks)) === canonical([`${this.project}-internal`]),
        "HTTP probe frontend attached to an unexpected network");
      require(["previous", "candidate"].some((pair) => metadata.Image === this.images[`${pair}-frontend`].id),
        "HTTP probe frontend is not one of the explicitly qualified images");
      const payload = Buffer.from(JSON.stringify({
        url, headers: requestHeaders, maxBytes: HTTP_MAX_RESPONSE_BYTES, timeoutMs: HTTP_TIMEOUT_SECONDS * 1000
      }));
      let result;
      try {
        result = this.docker(["exec", "-i", name, "node", "-e", NODE_HTTP_PROBE],
          { data: payload, timeout: HTTP_EXEC_TIMEOUT_SECONDS, check: false });
      } catch (error) {
        if (error instanceof CommandTimeout) throw new HttpTransportUnavailable("internal HTTP exec exceeded its hard deadline");
        throw error;
      }
      return decodeInternalResponse(result);
    }
    return this.hostRequest(url, requestHeaders);
  }

  hostRequest(url, headers) {
    return hostRequest(url, headers);
  }

  async waitHttp(service, requestPath) {
    const deadline = performance.now() + this.options.startupTimeout * 1000;
    let last = "not yet reachable";
    while (performance.now() < deadline) {
      try {
        const [status, , content] = await this.request(service, requestPath);
        last = `HTTP ${status}`;
        if (status === 200) return content;
      } catch (error) {
        if (!(error instanceof HttpTransportUnavailable)) throw error;
        last = error.constructor.name;
      }
      await sleep(2000);
    }
    throw new Error(`${service} startup exceeded deadline: ${last}`);
  }

  liveGuard() {
    const state = JSON.parse(this.sql(`
      select jsonb_build_object(
        'liveSetups', (select count(*) from ai_provider_setup where live_active),
        'credentials', (select count(*) from ai_provider_setup where credential_id is not null),
        'liveSettings', (select count(*) from ai_provider_settings where provider <> 'fake'),
        'pendingAiRequests', (select count(*) from ai_requests where status='pending'))
    `));
    assertNoLiveState(state);
    return state;
  }

  checkMigrations() {
    const history = JSON.parse(this.sql(`
      select jsonb_agg(jsonb_build_object('script',script,'version',version,'checksum',checksum)
         order by installed_rank) from flyway_schema_history where success and type='SQL'
    `));
    const files = migrationFiles();
    require(canonical(history.map((row) => row.script).sort()) === canonical(files.map((file) => path.basename(file)).sort()),
      "candidate image migrations do not match the current repository");
    const expected = Object.fromEntries(files.map((file) => [path.basename(file), flywayChecksum(file)]));
    require(history.every((row) => row.checksum === expected[row.script]),
      "candidate Flyway checksums do not match current migration source");
    this.evidence.migrations = history;
    this.evidence.migrationSourceSha256 = Object.fromEntries(files.map((file) => [path.basename(file), sha256(readFileSync(file))]));
  }

  snapshot(phase) {
    const tables = ["monitored_x_accounts", "source_posts", "article_sources", "articles", "outbox_events",
      "processed_events", "source_tombstones", "event_replay_suppressions", "newsletter_deliveries",
      "ai_provider_settings", "ai_provider_setup"];
    this.evidence.snapshotTables = tables;
    const data = Object.fromEntries(tables.map((table) => [table, JSON.parse(this.sql(
      `select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), '[]') from ${table} t`))]));
    require(Object.values(data).reduce((total, rows) => total + rows.length, 0) <= 100, "fixture snapshot exceeded 100 rows");
    // The credential guard precedes snapshots, so no ciphertext or live provider state is captured.
    const text = canonical(data);
    writeFileSync(path.join(this.output, `${phase}-data.json`), text + "\n");
    const [projected, observations] = snapshotProjection(data, this.sourceObservations ?? null);
    this.sourceObservations = observations;
    (this.evidence.sourceObservationMetadata ??= {})[phase] = observations;
    this.evidence.dataProjectionExcludes = { source_posts: ["last_checked_at", "version"] };
    (this.evidence.rawDataSha256 ??= {})[phase] = sha256(text);
    const raw = this.docker(["exec", `${this.project}-postgres`, "pg_dump", "-U", "news",
      "--schema-only", "--no-owner", "--no-acl", "news"]).stdout.toString();
    const schema = raw.split("\n").filter((line) => !line.startsWith("\\restrict ") && !line.startsWith("\\unrestrict "))
      .join("\n").replace(/\n*$/, "\n");
    require(Buffer.byteLength(schema) <= 2097152, "schema snapshot exceeded 2 MiB");
    writeFileSync(path.join(this.output, `${phase}-schema.sql`), schema);
    return { dataSha256: sha256(canonical(projected)), schemaSha256: sha256(schema) };
  }

  async phase(name, pair, initial = false) {
    this.evidence.activePhase = name;
    this.save();
    this.writeCompose(pair, pair === "candidate");
    if (initial) {
      for (const service of ["frontend", "backend", "management"]) await this.release(service);
    } else {
      for (const service of ["frontend", "backend"]) {
        owned(this.inspect("container", `${this.project}-${service}`), "container", `${this.project}-${service}`, this.project);
      }
      this.compose(["stop", "--timeout", "40", "frontend", "backend"], { timeout: 100 });
    }
    this.compose(["up", "-d", "--no-build", "--no-deps", "--pull", "never", "--force-recreate", "backend", "frontend"], { timeout: 120 });
    const backendHealth = JSON.parse(await this.waitHttp("management", "/actuator/health/readiness"));
    require(backendHealth.status === "UP", "actual readiness did not report UP");
    await this.waitHttp("frontend", "/sign-in");
    const containers = {};
    for (const service of ["backend", "frontend"]) {
      const metadata = this.inspect("container", `${this.project}-${service}`);
      owned(metadata, "container", `${this.project}-${service}`, this.project);
      require(metadata.Image === this.images[`${pair}-${service}`].id,
        "running container image differs from the immutable requested artifact");
      require(canonical(Object.keys(metadata.NetworkSettings.Networks)) === canonical([`${this.project}-internal`]),
        "application attached to an unexpected network");
      containers[service] = { id: metadata.Id, imageId: metadata.Image };
    }
    require(this.inspect("container", `${this.project}-postgres`).Id === this.databaseId,
      "database container changed during the binary sequence");
    if (pair === "candidate") this.checkMigrations();
    if (initial) {
      require(this.sql("select count(*) from articles") === "0", "expected a fresh schema without an editorial seed");
      this.sql(FIXTURE_SQL);
    }
    const liveState = this.liveGuard();
    const snapshot = this.snapshot(name);
    if (initial) this.baseline = snapshot;
    require(canonical(snapshot) === canonical(this.baseline), "schema or deterministic data changed across binary transition");
    const checks = [];
    const responses = {};
    const reader = { Authorization: "Basic " + Buffer.from("reader@example.test:reader-demo-password").toString("base64") };
    for (const service of ["backend", "frontend"]) {
      let [status, headers, content] = await this.request(service, `/api/v1/articles/${PUBLIC_SLUG}`);
      const article = status === 200 ? JSON.parse(content) : {};
      require(status === 200 && article.headline === PUBLIC_HEADLINE && article.body === BODY && article.slug === PUBLIC_SLUG,
        `${service} did not actually serve the retained public fixture`);
      const cacheControl = Object.entries(headers).find(([key]) => key.toLowerCase() === "cache-control")?.[1] || "";
      require(String(cacheControl).includes("no-store"), `${service} public response lost its no-store contract`);
      responses[service] = sha256(canonical(article));
      [status, , content] = await this.request(service, `/api/v1/articles/${PRIVATE_SLUG}`);
      require(status === 404 && !content.includes("Private fixture must not leak"), `${service} disclosed the private fixture`);
      require((await this.request(service, "/api/v1/admin/users"))[0] === 401, `${service} did not reject anonymous administration`);
      [status, , content] = await this.request(service, "/api/v1/auth/me", reader);
      require(status === 200 && JSON.parse(content).email === "reader@example.test",
        `${service} reader authentication did not actually succeed`);
      require((await this.request(service, "/api/v1/admin/users", reader))[0] === 403,
        `${service} did not deny a valid non-admin identity`);
      checks.push(`${service}-public-read`, `${service}-no-store`, `${service}-private-404`,
        `${service}-anonymous-401`, `${service}-reader-authenticated`, `${service}-reader-admin-403`);
    }
    require(responses.backend === responses.frontend, "frontend proxy changed the public article");
    if (initial) this.publicResponse = responses.backend;
    require(responses.backend === this.publicResponse, "public article representation changed between the specified binaries");
    const [pageStatus, , page] = await this.request("frontend", `/articles/${PUBLIC_SLUG}`, { Accept: "text/html" });
    require(pageStatus === 200 && page.includes(PUBLIC_HEADLINE) && page.includes(BODY),
      "actual frontend server rendering did not retain the public article");
    let [status] = await this.request("backend", "/actuator/prometheus");
    require(status === 401, `application-port metrics must deny anonymous API requests: HTTP ${status}`);
    [status] = await this.request("backend", "/actuator/prometheus", { "X-Forwarded-Port": "8081" });
    require(status === 401, `forwarded-port spoof must remain unauthorized: HTTP ${status}`);
    checks.push("frontend-real-ssr", "application-metrics-denied", "spoofed-port-denied");
    if (pair === "candidate") {
      const [metricsStatus, , metrics] = await this.request("management", "/actuator/prometheus");
      require(metricsStatus === 200 && metrics.includes("news_operations_collection_success"),
        `candidate operational scrape failed or lacked required metrics: HTTP ${metricsStatus}`);
      checks.push("candidate-real-prometheus-scrape");
    }
    require(this.sql("select count(*) from newsletter_deliveries") === "0",
      "unexpected newsletter delivery during read-only binary qualification");
    const [mailStatus, , mailbox] = await this.request("mailpit", "/api/v1/messages");
    require(mailStatus === 200 && JSON.parse(mailbox).total === 0, "unexpected email during read-only binary qualification");
    require(canonical(this.snapshot(`${name}-after-http`)) === canonical(this.baseline),
      "HTTP qualification unexpectedly mutated protected fixture state");
    this.evidence.phases.push({
      name, pair, containers, snapshot, httpTransport: this.options.httpTransport,
      publicResponseSha256: responses.backend, httpChecks: checks, liveGuard: liveState,
      newsletterDeliveries: 0, mailpitMessages: 0
    });
    this.logs(name);
    this.save();
  }

  logs(phase = "final") {
    for (const service of SERVICES) {
      const name = `${this.project}-${service}`;
      const metadata = this.inspect("container", name, { allowMissing: true });
      if (metadata === null) continue;
      owned(metadata, "container", name, this.project);
      const result = this.docker(["logs", "--tail", "100", name], { check: false });
      const content = this.redact(Buffer.concat([result.stdout, result.stderr]).toString());
      writeFileSync(path.join(this.output, `${phase}-${service}.log`), content.slice(-100000));
    }
  }

  cleanup() {
    if (!this.created) {
      this.evidence.cleanup = { ownedResourcesOnly: true, notStarted: true, errors: [] };
      this.save();
      return;
    }
    const errors = [];
    for (const [kind, name] of this.resources()) {
      try {
        const metadata = this.inspect(kind, name, { allowMissing: true });
        if (metadata === null) continue;
        owned(metadata, kind, name, this.project);
        if (kind === "container") {
          this.docker(["stop", "--time", "35", name], { timeout: 45, check: false });
          this.docker(["container", "rm", "-f", name]);
        } else {
          this.docker([kind, "rm", name]);
        }
      } catch (error) {
        errors.push(error.message);
      }
    }
    this.evidence.cleanup = { ownedResourcesOnly: true, errors };
    if (errors.length) this.evidence.status = "failed";
    this.save();
    require(!errors.length, "owned-resource cleanup incomplete; inspect evidence");
  }
}

export function parseOptions(argv) {
  let parsed;
  try {
    parsed = parseArgs({
      args: argv,
      options: {
        ...Object.fromEntries(APPLICATION_IMAGES.map((name) => [name, { type: "string" }])),
        output: { type: "string" },
        "http-transport": { type: "string", default: "host" },
        ...Object.fromEntries(["frontend", "backend", "management", "mailpit"].map((name) => [`${name}-port`, {
          type: "string", default: process.env[`ACCEPTANCE_${name.toUpperCase()}_PORT`] || "0"
        }])),
        "startup-timeout": { type: "string", default: "300" },
        "keep-on-failure": { type: "boolean", default: false }
      }
    }).values;
  } catch (error) {
    throw new UsageError(error.message);
  }
  for (const name of [...APPLICATION_IMAGES, "output"]) {
    if (!parsed[name]) throw new UsageError(`--${name} is required`);
  }
  if (!["host", "internal"].includes(parsed["http-transport"])) throw new UsageError("--http-transport must be host or internal");
  const options = { ...parsed, httpTransport: parsed["http-transport"], keepOnFailure: parsed["keep-on-failure"] };
  for (const name of APPLICATION_IMAGES) immutableReference(options[name]);
  for (const name of ["frontend", "backend", "management", "mailpit"]) options[`${name}-port`] = checkedPort(parsed[`${name}-port`]);
  if (!/^\d+$/.test(parsed["startup-timeout"])) throw new UsageError("--startup-timeout must be an integer");
  options.startupTimeout = Number(parsed["startup-timeout"]);
  return options;
}

const HELP = `Usage: binary-qualification.mjs --previous-backend REF --previous-frontend REF
  --candidate-backend REF --candidate-frontend REF --output DIR [--http-transport host|internal]
  [--frontend-port N] [--backend-port N] [--management-port N] [--mailpit-port N]
  [--startup-timeout SECONDS] [--keep-on-failure]

Requires four DISTINCT locally available immutable app images plus all dependency digests listed in
DEPENDENCIES. Uses one fresh database, fake providers and an ephemeral master key. Only the previous
pair disables Flyway; candidates validate current migration checksums. Ports default to free loopback
ports (or ACCEPTANCE_*_PORT). --http-transport internal executes real HTTP through node in the owned
frontend; it neither publishes nor qualifies host ports/ingress and never falls back from host mode.
This does not qualify live-provider credentials/settings, production or concurrent rolling replicas.
--keep-on-failure retains only this run's labelled resources.`;

async function main() {
  let options;
  try {
    options = parseOptions(process.argv.slice(2));
  } catch (error) {
    if (!(error instanceof UsageError)) throw error;
    console.error(`${error.message}\n\n${HELP}`);
    process.exit(2);
  }
  require(options.startupTimeout >= 60 && options.startupTimeout <= 600, "startup timeout must be between 60 and 600 seconds");
  const output = path.resolve(ROOT, options.output);
  require(output.startsWith(ROOT + path.sep) && !existsSync(output), "evidence must be a new directory below the repository");
  process.umask(0o077);
  mkdirSync(output, { recursive: true });
  const qualification = new Qualification(options, output);
  let finished = false;
  const finish = () => {
    if (finished) return;
    finished = true;
    let logError = null;
    try {
      if (qualification.created) qualification.logs();
    } catch (error) {
      logError = qualification.redact(error.message);
      qualification.evidence.logCollectionError = logError;
      qualification.evidence.status = "failed";
      qualification.evidence.failure ??= "Required final log collection failed";
    }
    try {
      if (options.keepOnFailure && qualification.evidence.status !== "passed") {
        qualification.evidence.cleanup = { retainedForInspection: true, ownedResourcesOnly: true };
        qualification.save();
      } else {
        qualification.cleanup();
      }
    } finally {
      console.log(JSON.stringify({ status: qualification.evidence.status, evidence: path.join(output, "evidence.json") }));
    }
    if (logError !== null) throw new Error("Required final log collection failed; inspect evidence");
  };
  for (const signal of ["SIGINT", "SIGTERM"]) {
    process.on(signal, () => {
      qualification.evidence.status = "failed";
      qualification.evidence.failure = `interrupted by signal ${signal}`;
      try {
        finish();
      } finally {
        process.exit(1);
      }
    });
  }
  try {
    await qualification.preflight();
    await qualification.startDependencies();
    await qualification.phase("candidate-initial", "candidate", true);
    await qualification.phase("previous-rollback", "previous");
    await qualification.phase("candidate-forward", "candidate");
    qualification.evidence.publishedPortAccessQualified = options.httpTransport === "host";
    qualification.evidence.status = "passed";
  } catch (error) {
    qualification.evidence.status = "failed";
    qualification.evidence.failure = qualification.redact(error.message);
    throw error;
  } finally {
    await qualification.releaseAll();
    finish();
  }
}

if (process.argv[1] && realpathSync(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
