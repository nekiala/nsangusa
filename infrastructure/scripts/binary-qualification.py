#!/usr/bin/env python3
"""Optional real-binary candidate -> previous -> candidate qualification; never builds or pulls."""
import argparse
import base64
from contextlib import ExitStack
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import signal
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid
import zlib

ROOT = Path(__file__).resolve().parents[2]
OWNER = "com.nsangusa.binary-qualification"
DEPENDENCIES = {
    "postgres": "postgres@sha256:a02db8cac496f15b094798a38254f14d6e00741f709360e5e00bb6668ea31636",
    "redis": "redis@sha256:344e3945a0b431c8ff1eecd58c5573538126bd756f02fc7e218ddf1fc2546366",
    "kafka": "apache/kafka@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837",
    "seaweedfs": "chrislusf/seaweedfs@sha256:4e61d15fd35994cb1e43e1e553dff106794841fd9a99ade2fc8c8bfce4d7872d",
    # Bucket creation uses the server image's curl rather than a separate client image.
    "seaweedfs-init": "chrislusf/seaweedfs@sha256:4e61d15fd35994cb1e43e1e553dff106794841fd9a99ade2fc8c8bfce4d7872d",
    "mailpit": "axllent/mailpit@sha256:6abc8e633df15eaf785cfcf38bae48e66f64beecdc03121e249d0f9ec15f0707",
}
CORE = ("postgres", "redis", "kafka", "seaweedfs", "mailpit")
SERVICES = (*CORE, "kafka-init", "seaweedfs-init", "backend", "frontend")
VOLUMES = ("postgres", "kafka", "kafka-secrets", "kafka-config", "seaweedfs", "mailpit")
PROTECTED_PORTS = {
    3000, 8080, 18025, 5432, 6379, 9092, 1025, 8025, 9000, 9001, 19001, 4317, 4318, 13133
}
PUBLIC_SLUG = "binary-qualification-public"
PRIVATE_SLUG = "binary-qualification-private"
PUBLIC_HEADLINE = "Binary qualification retained public article"
BODY = "Deterministic synthetic article retained across the actual application binaries."
HTTP_MAX_RESPONSE_BYTES = 2 * 1024 * 1024
HTTP_TIMEOUT_SECONDS = 15
HTTP_EXEC_TIMEOUT_SECONDS = 20
INTERNAL_HTTP_ORIGINS = {
    "frontend": "http://frontend:3000",
    "backend": "http://backend:8080",
    "management": "http://backend:8081",
    "mailpit": "http://mailpit:8025",
}

# node:http never follows redirects. The timer covers DNS, connection and the entire response body.
NODE_HTTP_PROBE = r"""
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
"""


class HttpTransportUnavailable(OSError):
    pass


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def http_target(transport, service, path, ports):
    require(transport in ("host", "internal"), "an explicit supported HTTP transport is required")
    require(service in INTERNAL_HTTP_ORIGINS, "HTTP target is not an approved service")
    require(isinstance(path, str) and path.startswith("/") and not path.startswith("//")
            and len(path) <= 2048 and not any(character in path for character in "\r\n\\#"),
            "HTTP target must be a bounded service-relative path")
    origin = (INTERNAL_HTTP_ORIGINS[service] if transport == "internal"
              else f"http://127.0.0.1:{ports[service]}")
    return origin + path


def decode_internal_response(result):
    max_envelope = ((HTTP_MAX_RESPONSE_BYTES + 2) // 3) * 4 + 65536
    require(len(result.stdout) <= max_envelope, "internal HTTP result exceeded its envelope bound")
    try:
        value = json.loads(result.stdout)
    except (ValueError, UnicodeError):
        raise RuntimeError("internal HTTP probe returned no valid result")
    require(isinstance(value, dict), "internal HTTP result must be an object")
    if result.returncode:
        if value.get("error") in ("unavailable", "timeout"):
            raise HttpTransportUnavailable("internal HTTP request " + value["error"])
        raise RuntimeError("internal HTTP probe rejected the request or response: " + str(value.get("error")))
    require("error" not in value and type(value.get("status")) is int and 100 <= value["status"] <= 599
            and type(value.get("bytes")) is int and 0 <= value["bytes"] <= HTTP_MAX_RESPONSE_BYTES
            and isinstance(value.get("headers"), dict) and isinstance(value.get("bodyBase64"), str),
            "internal HTTP result is incomplete or outside bounds")
    require(all(isinstance(key, str) and (isinstance(header, str)
                or isinstance(header, list) and all(isinstance(item, str) for item in header))
                for key, header in value["headers"].items()), "invalid HTTP response headers")
    try:
        body = base64.b64decode(value["bodyBase64"], validate=True)
    except ValueError:
        raise RuntimeError("internal HTTP body encoding is invalid")
    require(len(body) == value["bytes"] and len(body) <= HTTP_MAX_RESPONSE_BYTES,
            "internal HTTP body size does not match its bounded result")
    return value["status"], value["headers"], body


def immutable_reference(value):
    if re.fullmatch(r"sha256:[a-f0-9]{64}", value):
        return value
    if re.fullmatch(r"[a-z0-9][a-z0-9./:_-]*@sha256:[a-f0-9]{64}", value):
        return value
    raise argparse.ArgumentTypeError("Use a full local sha256 image ID or repository@sha256 digest, never a tag")


def checked_project(project):
    require(re.fullmatch(r"nsangusa-binary-[a-f0-9]{12}", project),
            "refusing any project outside the uniquely generated binary-qualification namespace")
    return project


def checked_port(value):
    try:
        port = int(value)
    except (ValueError, TypeError):
        raise argparse.ArgumentTypeError("port must be 0 (allocate) or an integer from 1024 to 65535")
    if str(value) != str(port) or (port != 0 and not 1024 <= port <= 65535):
        raise argparse.ArgumentTypeError("port must be 0 (allocate) or an integer from 1024 to 65535")
    if port in PROTECTED_PORTS:
        raise argparse.ArgumentTypeError("refusing a reserved preview/phase4 port")
    return port


def owned(metadata, kind, name, project):
    checked_project(project)
    expected = {f"{project}-{service}" for service in SERVICES}
    if kind == "volume":
        expected = {f"{project}-{volume}-data" for volume in VOLUMES}
    elif kind == "network":
        expected = {f"{project}-internal"}
    require(name in expected, "resource name is not in this run's explicit ownership inventory")
    labels = metadata.get("Config", {}).get("Labels", {}) if kind == "container" else metadata.get("Labels", {})
    require(labels and labels.get(OWNER) == project
            and labels.get("com.docker.compose.project") == project,
            f"refusing non-owned {kind}: {name}")
    require(metadata.get("Name", "").lstrip("/") == name,
            f"resource inspection returned a different name: {name}")


def assert_distinct_images(images):
    ids = [images[name]["id"] for name in
           ("previous-backend", "previous-frontend", "candidate-backend", "candidate-frontend")]
    require(len(set(ids)) == 4,
            "four distinct backend/frontend image IDs are required; unchanged images do not prove a transition")


def assert_no_live_state(state):
    expected = {"liveSetups", "credentials", "liveSettings", "pendingAiRequests"}
    require(isinstance(state, dict) and set(state) == expected
            and all(type(value) is int and value == 0 for value in state.values()),
            "live settings, credentials or in-flight AI work found; live-provider rollback is NOT qualified")


def snapshot_projection(data, previous=None):
    projected = {table: [dict(row) for row in rows] for table, rows in data.items()}
    observations = {}
    for row in projected["source_posts"]:
        version = row.pop("version")
        checked = row.pop("last_checked_at")
        require(type(version) is int and version >= 0, "invalid source observation version")
        observed = None if checked is None else datetime.fromisoformat(checked)
        require(observed is None or observed.utcoffset() is not None,
                "source observation must have an explicit timezone")
        observations[row["id"]] = {"version": version, "last_checked_at": checked}
        if previous is not None:
            require(row["id"] in previous, "source identity changed across snapshots")
            before = previous[row["id"]]
            before_time = (None if before["last_checked_at"] is None
                           else datetime.fromisoformat(before["last_checked_at"]))
            unchanged = version == before["version"] and observed == before_time
            advanced = (version > before["version"] and observed is not None
                        and (before_time is None or observed > before_time))
            require(unchanged or advanced, "source observation metadata regressed or changed inconsistently")
    require(previous is None or observations.keys() == previous.keys(),
            "source identity changed across snapshots")
    return projected, observations


def flyway_checksum(path):
    crc = 0
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        crc = zlib.crc32(line.encode("utf-8"), crc)
    return crc if crc < 2 ** 31 else crc - 2 ** 32


def compose_document(project, images, ports, pair, flyway_enabled, http_transport="host"):
    checked_project(project)
    require(http_transport in ("host", "internal"), "unsupported HTTP transport")
    labels = {OWNER: project}

    def service(name, image, memory, **extra):
        return {"image": image, "pull_policy": "never", "container_name": f"{project}-{name}",
                "restart": "no", "labels": labels.copy(), "networks": ["isolated"],
                "cpus": 0.5, "mem_limit": memory, "memswap_limit": memory, "pids_limit": 192,
                "tmpfs": ["/work:rw,noexec,nosuid,size=128m,mode=1777"], **extra}

    def port(host, target):
        return {"host_ip": "127.0.0.1", "published": str(host), "target": target, "protocol": "tcp"}

    def health(command):
        return {"test": command, "interval": "5s", "timeout": "5s", "retries": 30}

    services = {
        "postgres": service("postgres", images["postgres"]["id"], "384m",
            environment={"POSTGRES_DB": "news", "POSTGRES_USER": "news",
                         "POSTGRES_PASSWORD": "synthetic-binary-only", "TMPDIR": "/work"},
            command=["postgres", "-c", "shared_buffers=64MB", "-c", "max_connections=20",
                     "-c", "statement_timeout=15000", "-c", "lock_timeout=5000"],
            volumes=["postgres-data:/var/lib/postgresql"],
            healthcheck=health(["CMD", "pg_isready", "-U", "news", "-d", "news"])),
        "redis": service("redis", images["redis"]["id"], "96m",
            command=["redis-server", "--appendonly", "no", "--save", "", "--protected-mode", "no",
                     "--maxmemory", "64mb", "--maxmemory-policy", "noeviction"],
            healthcheck=health(["CMD", "redis-cli", "ping"])),
        "kafka": service("kafka", images["kafka"]["id"], "768m",
            hostname="kafka", volumes=["kafka-data:/var/lib/kafka/data",
                                     "kafka-secrets-data:/etc/kafka/secrets",
                                     "kafka-config-data:/mnt/shared/config"],
            environment={
                "KAFKA_NODE_ID": "1", "KAFKA_PROCESS_ROLES": "broker,controller",
                "KAFKA_LISTENERS": "CONTROLLER://:9093,INTERNAL://:29092",
                "KAFKA_ADVERTISED_LISTENERS": "INTERNAL://kafka:29092",
                "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP": "CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT",
                "KAFKA_CONTROLLER_LISTENER_NAMES": "CONTROLLER",
                "KAFKA_INTER_BROKER_LISTENER_NAME": "INTERNAL",
                "KAFKA_CONTROLLER_QUORUM_VOTERS": "1@kafka:9093",
                "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR": "1",
                "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR": "1",
                "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR": "1",
                "KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS": "0",
                "KAFKA_NUM_PARTITIONS": "3", "KAFKA_AUTO_CREATE_TOPICS_ENABLE": "false",
                "KAFKA_LOG_DIRS": "/var/lib/kafka/data", "CLUSTER_ID": "MkU3OEVBNTcwNTJENDM2Qk",
                "KAFKA_HEAP_OPTS": "-Xms128m -Xmx256m -Djava.io.tmpdir=/work"},
            healthcheck={**health(["CMD-SHELL",
                "/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --list >/dev/null 2>&1"]),
                "interval": "10s", "timeout": "10s", "start_period": "20s", "retries": 18}),
        "seaweedfs": service("seaweedfs", images["seaweedfs"]["id"], "256m",
            command=["server", "-dir=/data", "-s3", "-s3.port=9000", "-filer.disableHttp",
                     "-master.telemetry=false", "-master.volumeSizeLimitMB=64", "-volume.max=32"],
            environment={"AWS_ACCESS_KEY_ID": "synthetic", "AWS_SECRET_ACCESS_KEY": "synthetic-binary-only",
                         "GOMEMLIMIT": "192MiB", "GOMAXPROCS": "2"},
            volumes=["seaweedfs-data:/data"],
            healthcheck=health(["CMD", "curl", "-fsS", "http://127.0.0.1:9000/healthz"])),
        "mailpit": service("mailpit", images["mailpit"]["id"], "64m",
            environment={"MP_MAX_MESSAGES": "20", "MP_DATABASE": "/data/mailpit.db", "TMPDIR": "/work"},
            volumes=["mailpit-data:/data"], ports=[port(ports["mailpit"], 8025)],
            healthcheck=health(["CMD", "/mailpit", "readyz"])),
    }
    services["kafka-init"] = service("kafka-init", images["kafka"]["id"], "256m",
        environment={"KAFKA_HEAP_OPTS": "-Xms32m -Xmx128m -Djava.io.tmpdir=/work"},
        volumes=["kafka-data:/var/lib/kafka/data:ro", "kafka-secrets-data:/etc/kafka/secrets:ro",
                 "kafka-config-data:/mnt/shared/config:ro"],
        entrypoint=["/bin/bash", "-ec"],
        command=["for topic in news.ingestion.v1 news.ingestion.v1.dlt news.editorial.v1 "
                 "news.editorial.v1.dlt news.publication.v1 news.publication.v1.dlt "
                 "news.notifications.v1 news.notifications.v1.dlt; do "
                 "/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 "
                 "--create --if-not-exists --topic \"$$topic\" --partitions 3 --replication-factor 1; done"])
    services["seaweedfs-init"] = service("seaweedfs-init", images["seaweedfs-init"]["id"], "64m",
        entrypoint=["/bin/sh", "-ec"],
        command=["status=\"$$(curl -sS -o /dev/null -w '%{http_code}' -X PUT "
                 "--aws-sigv4 aws:amz:us-east-1:s3 --user synthetic:synthetic-binary-only "
                 "http://seaweedfs:9000/news-media)\"; "
                 "case \"$$status\" in 200|409) ;; *) echo \"bucket creation failed: $$status\" >&2; exit 1 ;; esac"])
    public_url = f"http://127.0.0.1:{ports['frontend']}"
    services["backend"] = service("backend", images[f"{pair}-backend"]["id"], "1024m",
        cpus=1.0, stop_grace_period="40s", healthcheck={"disable": True},
        environment={
            "SPRING_PROFILES_ACTIVE": "local", "SPRING_DOCKER_COMPOSE_ENABLED": "false",
            "SPRING_FLYWAY_ENABLED": str(flyway_enabled).lower(), "LOCAL_SEED": "false",
            "DATABASE_URL": "jdbc:postgresql://postgres:5432/news", "DATABASE_USERNAME": "news",
            "DATABASE_PASSWORD": "synthetic-binary-only", "DATABASE_POOL_SIZE": "4",
            "KAFKA_BOOTSTRAP_SERVERS": "kafka:29092", "REDIS_HOST": "redis",
            "MAIL_HOST": "mailpit", "MAIL_PORT": "1025",
            "S3_ENDPOINT": "http://seaweedfs:9000", "S3_ACCESS_KEY": "synthetic",
            "S3_SECRET_KEY": "synthetic-binary-only", "S3_SERVER_SIDE_ENCRYPTION": "none",
            "PUBLIC_BASE_URL": public_url, "COOKIE_SECURE": "false", "PROVIDER_MODE": "fake",
            "AI_LIVE_ENABLED": "false", "AI_API_KEY": "", "IMAGE_API_KEY": "",
            "AI_CREDENTIAL_MASTER_KEY": "${BINARY_MASTER_KEY:?Provided ephemerally by the runner}",
            "MANAGEMENT_SERVER_PORT": "8081",
            "NEWS_OPERATIONS_INTERNAL_METRICS_ENABLED": "true", "MANAGEMENT_TRACING_ENABLED": "false",
            "MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED": "false",
            "OTEL_SDK_DISABLED": "true", "OTEL_METRICS_EXPORTER": "none", "TMPDIR": "/work",
            "JAVA_TOOL_OPTIONS": "-XX:MaxRAMPercentage=55 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/work"},
        ports=[port(ports["backend"], 8080), port(ports["management"], 8081)])
    services["frontend"] = service("frontend", images[f"{pair}-frontend"]["id"], "384m",
        stop_grace_period="20s", healthcheck={"disable": True},
        environment={"NODE_ENV": "production", "NEXT_TELEMETRY_DISABLED": "1",
                     "NSANGUSA_API_URL": "http://backend:8080", "PUBLIC_BASE_URL": public_url,
                     "NEXT_PUBLIC_SITE_URL": public_url, "NEXT_PUBLIC_API_MODE": "api",
                     "TMPDIR": "/work", "NODE_OPTIONS": "--max-old-space-size=256"},
        ports=[port(ports["frontend"], 3000)])
    if http_transport == "internal":
        for definition in services.values():
            definition.pop("ports", None)
    return {"name": project, "services": services,
            "networks": {"isolated": {"name": f"{project}-internal", "internal": True, "labels": labels}},
            "volumes": {f"{name}-data": {"name": f"{project}-{name}-data", "labels": labels}
                        for name in VOLUMES}}


FIXTURE_SQL = """
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
"""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, stream, code, message, headers, new_url):
        return None


class Qualification:
    def __init__(self, args, output):
        self.args = args
        self.output = output
        self.project = checked_project("nsangusa-binary-" + uuid.uuid4().hex[:12])
        self.config = output / "compose.json"
        self.environment = os.environ.copy()
        self.master_key = base64.b64encode(secrets.token_bytes(32)).decode()
        self.environment["BINARY_MASTER_KEY"] = self.master_key
        self.images = {}
        self.ports = {}
        self.reservations = {}
        self.created = False
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        self.evidence = {
            "schemaVersion": 1, "kind": "isolated-real-binary-rollback-forward",
            "status": "running", "project": self.project, "phases": [],
            "liveProviderRollbackQualified": False, "productionDeployment": False,
            "productionSloQualified": False, "paidProviderCallsPermitted": False,
            "httpTransport": args.http_transport,
            "publishedPortAccessQualified": False, "ingressAccessQualified": False,
            "fixtureScope": "pre-existing synthetic business rows; monotonic source observations allowed; "
                            "no publication mutation qualification",
            "limits": {"steadyStateMemoryMiB": 2976, "initializerMemoryMiB": 256,
                       "backendHeapPercent": 55, "databasePoolSize": 4, "fixtureArticles": 2,
                       "maxHttpResponseBytes": HTTP_MAX_RESPONSE_BYTES,
                       "internalHttpDeadlineSeconds": HTTP_TIMEOUT_SECONDS,
                       "internalHttpExecTimeoutSeconds": HTTP_EXEC_TIMEOUT_SECONDS,
                       "perPhaseStartupSeconds": args.startup_timeout,
                       "internetEgress": "blocked by owned internal network"},
            "unqualified": ["live provider credentials/settings and active requests",
                            "concurrent rolling replicas and sustained traffic",
                            "managed PITR/failover, TLS/ACLs and production deployment",
                            "editorial mutations, broker catch-up and newsletter delivery"]}
        if args.http_transport == "internal":
            self.evidence["unqualified"].append("published host ports and ingress access")
        self.save()

    def save(self):
        (self.output / "evidence.json").write_text(json.dumps(self.evidence, indent=2) + "\n")

    def command(self, arguments, data=None, timeout=90, check=True):
        result = subprocess.run(arguments, cwd=ROOT, env=self.environment, input=data,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        if check and result.returncode:
            message = result.stderr.decode(errors="replace").replace(self.master_key, "[redacted]")
            raise RuntimeError(f"{arguments[:3]} exited {result.returncode}: {message[-4000:]}")
        return result

    def docker(self, *arguments, **options):
        return self.command(["docker", *arguments], **options)

    def compose(self, *arguments, **options):
        return self.docker("compose", "--project-name", self.project, "--file", str(self.config),
                           *arguments, **options)

    def inspect(self, kind, name, allow_missing=False, timeout=90):
        result = self.docker(kind, "inspect", name, check=False, timeout=timeout)
        if result.returncode:
            missing = any(text in result.stderr.decode().lower()
                          for text in ("no such object", "no such container", "no such volume",
                                       "no such network", f"network {name} not found"))
            require(allow_missing and missing, f"cannot inspect {kind} {name}")
            return None
        return json.loads(result.stdout)[0]

    def resources(self):
        ordered = ("frontend", "backend", "seaweedfs-init", "kafka-init", *reversed(CORE))
        return ([("container", f"{self.project}-{name}") for name in ordered]
                + [("volume", f"{self.project}-{name}-data") for name in VOLUMES]
                + [("network", f"{self.project}-internal")])

    def preflight(self, stack):
        host = os.environ.get("DOCKER_HOST")
        if not host:
            context = self.docker("context", "show").stdout.decode().strip()
            host = json.loads(self.docker("context", "inspect", context).stdout)[0]["Endpoints"]["docker"]["Host"]
        require(host.startswith(("unix://", "npipe://")), "a local Docker engine is required")
        references = {**DEPENDENCIES, **{
            name: getattr(self.args, name.replace("-", "_"))
            for name in ("previous-backend", "previous-frontend", "candidate-backend", "candidate-frontend")}}
        for name, reference in references.items():
            immutable_reference(reference)
            result = self.docker("image", "inspect", reference, check=False)
            require(result.returncode == 0, f"immutable image must already be available locally: {reference}")
            image = json.loads(result.stdout)[0]
            expected_volumes = {
                "postgres": {"/var/lib/postgresql"},
                "kafka": {"/var/lib/kafka/data", "/etc/kafka/secrets", "/mnt/shared/config"},
                "seaweedfs": {"/data"},
            }.get(name, set())
            require(set(image["Config"].get("Volumes") or {}) <= expected_volumes,
                    f"image declares unmapped anonymous volumes; explicit owned mapping required: {name}")
            self.images[name] = {"reference": reference, "id": image["Id"],
                                 "platform": f"{image.get('Os')}/{image.get('Architecture')}"}
        assert_distinct_images(self.images)
        for kind, name in self.resources():
            require(self.inspect(kind, name, allow_missing=True) is None,
                    f"refusing an existing resource: {name}")
        for name in ("frontend", "backend", "management", "mailpit"):
            requested = getattr(self.args, name + "_port")
            reservation = stack.enter_context(socket.socket(socket.AF_INET, socket.SOCK_STREAM))
            reservation.bind(("127.0.0.1", requested))
            selected = reservation.getsockname()[1]
            require(selected not in PROTECTED_PORTS and selected not in self.ports.values(),
                    "ports must be distinct and cannot reuse protected stacks")
            self.ports[name] = selected
            self.reservations[name] = reservation
        self.evidence["images"] = self.images
        self.evidence["ports"] = self.ports
        self.evidence["portsPurpose"] = (
            "public-origin configuration only; no host ports published or tested"
            if self.args.http_transport == "internal" else "published loopback HTTP endpoints")
        self.evidence["httpEndpoints"] = {
            service: http_target(self.args.http_transport, service, "/", self.ports)
            for service in INTERNAL_HTTP_ORIGINS}
        self.evidence["ownedResources"] = self.resources()
        self.save()

    def write_compose(self, pair, flyway):
        document = compose_document(self.project, self.images, self.ports, pair, flyway,
                                    self.args.http_transport)
        self.config.write_text(json.dumps(document, indent=2) + "\n")

    def start_dependencies(self):
        self.write_compose("candidate", True)
        self.compose("config", "--quiet")
        self.reservations["mailpit"].close()
        self.created = True
        self.compose("up", "-d", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "180",
                     *CORE, timeout=210)
        for name in ("kafka-init", "seaweedfs-init"):
            self.compose("run", "--rm", "--no-deps", "--pull", "never", "--name",
                         f"{self.project}-{name}", name, timeout=180)
        network = self.inspect("network", f"{self.project}-internal")
        owned(network, "network", f"{self.project}-internal", self.project)
        require(network["Internal"] is True, "network must prohibit external egress")
        for volume in VOLUMES:
            name = f"{self.project}-{volume}-data"
            owned(self.inspect("volume", name), "volume", name, self.project)
        database = self.inspect("container", f"{self.project}-postgres")
        owned(database, "container", f"{self.project}-postgres", self.project)
        self.database_id = database["Id"]

    def sql(self, text):
        return self.docker("exec", "-i", f"{self.project}-postgres", "psql", "-X", "-qAt",
                           "-v", "ON_ERROR_STOP=1", "-U", "news", "-d", "news",
                           data=text.encode()).stdout.decode().strip()

    def request(self, service, path, headers=None):
        url = http_target(self.args.http_transport, service, path, self.ports)
        accept = "*/*" if path == "/actuator/prometheus" else "application/json"
        request_headers = {"Accept": accept, "Accept-Encoding": "identity", **(headers or {})}
        if self.args.http_transport == "internal":
            name = f"{self.project}-frontend"
            metadata = self.inspect("container", name, timeout=5)
            owned(metadata, "container", name, self.project)
            require(metadata.get("State", {}).get("Running") is True,
                    "owned frontend must be running before executing an HTTP probe")
            require(set(metadata["NetworkSettings"]["Networks"]) == {f"{self.project}-internal"},
                    "HTTP probe frontend attached to an unexpected network")
            require(metadata["Image"] in {self.images[f"{pair}-frontend"]["id"]
                                         for pair in ("previous", "candidate")},
                    "HTTP probe frontend is not one of the explicitly qualified images")
            payload = json.dumps({"url": url, "headers": request_headers,
                                  "maxBytes": HTTP_MAX_RESPONSE_BYTES,
                                  "timeoutMs": HTTP_TIMEOUT_SECONDS * 1000}).encode()
            try:
                result = self.docker("exec", "-i", name, "node", "-e", NODE_HTTP_PROBE,
                                     data=payload, timeout=HTTP_EXEC_TIMEOUT_SECONDS, check=False)
            except subprocess.TimeoutExpired:
                raise HttpTransportUnavailable("internal HTTP exec exceeded its hard deadline")
            return decode_internal_response(result)
        request = urllib.request.Request(url, headers=request_headers)
        try:
            response = self.http.open(request, timeout=HTTP_TIMEOUT_SECONDS)
        except urllib.error.HTTPError as response_error:
            response = response_error
        with response:
            content = response.read(HTTP_MAX_RESPONSE_BYTES + 1)
            require(len(content) <= HTTP_MAX_RESPONSE_BYTES, "HTTP response exceeds qualification bound")
            return response.status, dict(response.headers), content

    def wait_http(self, service, path):
        deadline = time.monotonic() + self.args.startup_timeout
        last = "not yet reachable"
        while time.monotonic() < deadline:
            try:
                status, _, content = self.request(service, path)
                last = f"HTTP {status}"
                if status == 200:
                    return content
            except (OSError, urllib.error.URLError) as error:
                last = type(error).__name__
            time.sleep(2)
        raise RuntimeError(f"{service} startup exceeded deadline: {last}")

    def live_guard(self):
        state = json.loads(self.sql("""
          select jsonb_build_object(
            'liveSetups', (select count(*) from ai_provider_setup where live_active),
            'credentials', (select count(*) from ai_provider_setup where credential_id is not null),
            'liveSettings', (select count(*) from ai_provider_settings where provider <> 'fake'),
            'pendingAiRequests', (select count(*) from ai_requests where status='pending'))
          """))
        assert_no_live_state(state)
        return state

    def check_migrations(self):
        history = json.loads(self.sql("""
          select jsonb_agg(jsonb_build_object('script',script,'version',version,'checksum',checksum)
             order by installed_rank) from flyway_schema_history where success and type='SQL'
          """))
        files = sorted((ROOT / "backend/src/main/resources/db/migration").glob("V*.sql"),
                       key=lambda path: int(path.name.split("__")[0][1:]))
        require({row["script"] for row in history} == {path.name for path in files},
                "candidate image migrations do not match the current repository")
        expected = {path.name: flyway_checksum(path) for path in files}
        require(all(row["checksum"] == expected[row["script"]] for row in history),
                "candidate Flyway checksums do not match current migration source")
        self.evidence["migrations"] = history
        self.evidence["migrationSourceSha256"] = {
            path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in files}

    def snapshot(self, phase):
        tables = ("monitored_x_accounts", "source_posts", "article_sources", "articles", "outbox_events",
                  "processed_events", "source_tombstones", "event_replay_suppressions", "newsletter_deliveries",
                  "ai_provider_settings", "ai_provider_setup")
        self.evidence["snapshotTables"] = list(tables)
        data = {table: json.loads(self.sql(
            f"select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), '[]') from {table} t"))
                for table in tables}
        require(sum(len(rows) for rows in data.values()) <= 100, "fixture snapshot exceeded 100 rows")
        # The credential guard precedes snapshots, so no ciphertext or live provider state is captured.
        text = json.dumps(data, sort_keys=True, separators=(",", ":"))
        (self.output / f"{phase}-data.json").write_text(text + "\n")
        projected, observations = snapshot_projection(data, getattr(self, "source_observations", None))
        self.source_observations = observations
        self.evidence.setdefault("sourceObservationMetadata", {})[phase] = observations
        self.evidence["dataProjectionExcludes"] = {"source_posts": ["last_checked_at", "version"]}
        self.evidence.setdefault("rawDataSha256", {})[phase] = hashlib.sha256(text.encode()).hexdigest()
        comparable = json.dumps(projected, sort_keys=True, separators=(",", ":"))
        raw = self.docker("exec", f"{self.project}-postgres", "pg_dump", "-U", "news",
                          "--schema-only", "--no-owner", "--no-acl", "news").stdout.decode()
        schema = "\n".join(line for line in raw.splitlines()
                           if not line.startswith(("\\restrict ", "\\unrestrict "))) + "\n"
        require(len(schema.encode()) <= 2097152, "schema snapshot exceeded 2 MiB")
        (self.output / f"{phase}-schema.sql").write_text(schema)
        return {"dataSha256": hashlib.sha256(comparable.encode()).hexdigest(),
                "schemaSha256": hashlib.sha256(schema.encode()).hexdigest()}

    def phase(self, name, pair, initial=False):
        self.evidence["activePhase"] = name
        self.save()
        self.write_compose(pair, pair == "candidate")
        if initial:
            for service in ("frontend", "backend", "management"):
                self.reservations[service].close()
        else:
            for service in ("frontend", "backend"):
                owned(self.inspect("container", f"{self.project}-{service}"), "container",
                      f"{self.project}-{service}", self.project)
            self.compose("stop", "--timeout", "40", "frontend", "backend", timeout=100)
        self.compose("up", "-d", "--no-build", "--no-deps", "--pull", "never",
                     "--force-recreate", "backend", "frontend", timeout=120)
        backend_health = json.loads(self.wait_http("management", "/actuator/health/readiness"))
        require(backend_health.get("status") == "UP", "actual readiness did not report UP")
        self.wait_http("frontend", "/sign-in")
        containers = {}
        for service in ("backend", "frontend"):
            metadata = self.inspect("container", f"{self.project}-{service}")
            owned(metadata, "container", f"{self.project}-{service}", self.project)
            require(metadata["Image"] == self.images[f"{pair}-{service}"]["id"],
                    "running container image differs from the immutable requested artifact")
            require(set(metadata["NetworkSettings"]["Networks"]) == {f"{self.project}-internal"},
                    "application attached to an unexpected network")
            containers[service] = {"id": metadata["Id"], "imageId": metadata["Image"]}
        require(self.inspect("container", f"{self.project}-postgres")["Id"] == self.database_id,
                "database container changed during the binary sequence")
        if pair == "candidate":
            self.check_migrations()
        if initial:
            require(self.sql("select count(*) from articles") == "0",
                    "expected a fresh schema without an editorial seed")
            self.sql(FIXTURE_SQL)
        live_state = self.live_guard()
        snapshot = self.snapshot(name)
        if initial:
            self.baseline = snapshot
        require(snapshot == self.baseline, "schema or deterministic data changed across binary transition")
        checks = []
        responses = {}
        reader = {"Authorization": "Basic " + base64.b64encode(
            b"reader@example.test:reader-demo-password").decode()}
        for service in ("backend", "frontend"):
            status, headers, content = self.request(service, f"/api/v1/articles/{PUBLIC_SLUG}")
            article = json.loads(content) if status == 200 else {}
            require(status == 200 and article.get("headline") == PUBLIC_HEADLINE
                    and article.get("body") == BODY and article.get("slug") == PUBLIC_SLUG,
                    f"{service} did not actually serve the retained public fixture")
            require("no-store" in {k.lower(): v for k, v in headers.items()}.get("cache-control", ""),
                    f"{service} public response lost its no-store contract")
            digest = hashlib.sha256(json.dumps(article, sort_keys=True).encode()).hexdigest()
            responses[service] = digest
            status, _, content = self.request(service, f"/api/v1/articles/{PRIVATE_SLUG}")
            require(status == 404 and b"Private fixture must not leak" not in content,
                    f"{service} disclosed the private fixture")
            require(self.request(service, "/api/v1/admin/users")[0] == 401,
                    f"{service} did not reject anonymous administration")
            status, _, content = self.request(service, "/api/v1/auth/me", reader)
            require(status == 200 and json.loads(content).get("email") == "reader@example.test",
                    f"{service} reader authentication did not actually succeed")
            require(self.request(service, "/api/v1/admin/users", reader)[0] == 403,
                    f"{service} did not deny a valid non-admin identity")
            checks.extend([f"{service}-public-read", f"{service}-no-store", f"{service}-private-404",
                           f"{service}-anonymous-401", f"{service}-reader-authenticated",
                           f"{service}-reader-admin-403"])
        require(responses["backend"] == responses["frontend"], "frontend proxy changed the public article")
        if initial:
            self.public_response = responses["backend"]
        require(responses["backend"] == self.public_response,
                "public article representation changed between the specified binaries")
        status, _, page = self.request("frontend", f"/articles/{PUBLIC_SLUG}", {"Accept": "text/html"})
        require(status == 200 and PUBLIC_HEADLINE.encode() in page and BODY.encode() in page,
                "actual frontend server rendering did not retain the public article")
        status, _, _ = self.request("backend", "/actuator/prometheus")
        require(status == 401, f"application-port metrics must deny anonymous API requests: HTTP {status}")
        status, _, _ = self.request("backend", "/actuator/prometheus", {"X-Forwarded-Port": "8081"})
        require(status == 401, f"forwarded-port spoof must remain unauthorized: HTTP {status}")
        checks.extend(["frontend-real-ssr", "application-metrics-denied", "spoofed-port-denied"])
        if pair == "candidate":
            status, _, metrics = self.request("management", "/actuator/prometheus")
            require(status == 200 and b"news_operations_collection_success" in metrics,
                    f"candidate operational scrape failed or lacked required metrics: HTTP {status}")
            checks.append("candidate-real-prometheus-scrape")
        require(self.sql("select count(*) from newsletter_deliveries") == "0",
                "unexpected newsletter delivery during read-only binary qualification")
        status, _, mailbox = self.request("mailpit", "/api/v1/messages")
        require(status == 200 and json.loads(mailbox).get("total") == 0,
                "unexpected email during read-only binary qualification")
        require(self.snapshot(name + "-after-http") == self.baseline,
                "HTTP qualification unexpectedly mutated protected fixture state")
        self.evidence["phases"].append({
            "name": name, "pair": pair, "containers": containers, "snapshot": snapshot,
            "httpTransport": self.args.http_transport,
            "publicResponseSha256": responses["backend"], "httpChecks": checks,
            "liveGuard": live_state, "newsletterDeliveries": 0, "mailpitMessages": 0})
        self.logs(name)
        self.save()

    def logs(self, phase="final"):
        for service in SERVICES:
            name = f"{self.project}-{service}"
            metadata = self.inspect("container", name, allow_missing=True)
            if metadata is None:
                continue
            owned(metadata, "container", name, self.project)
            result = self.docker("logs", "--tail", "100", name, check=False)
            content = (result.stdout + result.stderr).decode(errors="replace").replace(self.master_key, "[redacted]")
            (self.output / f"{phase}-{service}.log").write_text(content[-100000:])

    def cleanup(self):
        if not self.created:
            self.evidence["cleanup"] = {"ownedResourcesOnly": True, "notStarted": True, "errors": []}
            self.save()
            return
        errors = []
        for kind, name in self.resources():
            try:
                metadata = self.inspect(kind, name, allow_missing=True)
                if metadata is None:
                    continue
                owned(metadata, kind, name, self.project)
                if kind == "container":
                    self.docker("stop", "--time", "35", name, timeout=45, check=False)
                    self.docker("container", "rm", "-f", name)
                else:
                    self.docker(kind, "rm", name)
            except Exception as error:
                errors.append(str(error))
        self.evidence["cleanup"] = {"ownedResourcesOnly": True, "errors": errors}
        if errors:
            self.evidence["status"] = "failed"
        self.save()
        require(not errors, "owned-resource cleanup incomplete; inspect evidence")


def parser():
    result = argparse.ArgumentParser(
        description=__doc__,
        epilog="Requires four DISTINCT locally available immutable app images plus all dependency "
               "digests listed in DEPENDENCIES. Uses one fresh database, fake providers and an "
               "ephemeral master key. Only the previous pair disables Flyway; candidates validate "
               "current migration checksums. Ports default to free loopback ports (or ACCEPTANCE_*_PORT). "
               "--http-transport internal executes real HTTP through node in the owned frontend; "
               "it neither publishes nor qualifies host ports/ingress and never falls back from host mode. "
               "This does not qualify live-provider credentials/settings, production or concurrent "
               "rolling replicas. --keep-on-failure retains only this run's labelled resources.")
    for name in ("previous-backend", "previous-frontend", "candidate-backend", "candidate-frontend"):
        result.add_argument("--" + name, type=immutable_reference, required=True)
    result.add_argument("--output", required=True, help="New evidence directory below the repository")
    result.add_argument("--http-transport", choices=("host", "internal"), default="host",
                        help="host loopback (default) or explicit owned-network HTTP via frontend node")
    for name in ("frontend", "backend", "management", "mailpit"):
        result.add_argument("--" + name + "-port", type=checked_port,
                            default=os.environ.get(f"ACCEPTANCE_{name.upper()}_PORT", "0"))
    result.add_argument("--startup-timeout", type=int, default=300)
    result.add_argument("--keep-on-failure", action="store_true")
    return result


def main():
    args = parser().parse_args()
    require(60 <= args.startup_timeout <= 600, "startup timeout must be between 60 and 600 seconds")
    output = (ROOT / args.output).resolve()
    require(output.is_relative_to(ROOT) and output != ROOT and not output.exists(),
            "evidence must be a new directory below the repository")
    os.umask(0o077)
    output.mkdir(parents=True)
    qualification = Qualification(args, output)

    def interrupted(signum, _frame):
        raise RuntimeError(f"interrupted by signal {signum}")

    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        with ExitStack() as stack:
            qualification.preflight(stack)
            qualification.start_dependencies()
            qualification.phase("candidate-initial", "candidate", initial=True)
            qualification.phase("previous-rollback", "previous")
            qualification.phase("candidate-forward", "candidate")
            qualification.evidence["publishedPortAccessQualified"] = args.http_transport == "host"
            qualification.evidence["status"] = "passed"
    except Exception as error:
        qualification.evidence["status"] = "failed"
        qualification.evidence["failure"] = str(error).replace(qualification.master_key, "[redacted]")
        raise
    finally:
        log_error = None
        try:
            if qualification.created:
                qualification.logs()
        except Exception as error:
            log_error = str(error).replace(qualification.master_key, "[redacted]")
            qualification.evidence["logCollectionError"] = log_error
            qualification.evidence["status"] = "failed"
            qualification.evidence.setdefault("failure", "Required final log collection failed")
        if args.keep_on_failure and qualification.evidence["status"] != "passed":
            qualification.evidence["cleanup"] = {"retainedForInspection": True,
                                                 "ownedResourcesOnly": True}
            qualification.save()
        else:
            qualification.cleanup()
        print(json.dumps({"status": qualification.evidence["status"], "evidence": str(output / "evidence.json")}))
        if log_error is not None:
            raise RuntimeError("Required final log collection failed; inspect evidence")


if __name__ == "__main__":
    main()
