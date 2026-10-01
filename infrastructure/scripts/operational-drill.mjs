#!/usr/bin/env node
// Bounded synthetic logical restore drill. Never connects to an operator database or host port.
import { spawnSync } from "node:child_process";
import { createHash, createHmac, randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import { chmodSync, existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { gunzipSync, gzipSync } from "node:zlib";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const POSTGRES = "postgres@sha256:a02db8cac496f15b094798a38254f14d6e00741f709360e5e00bb6668ea31636";
const SEAWEEDFS = "chrislusf/seaweedfs@sha256:4e61d15fd35994cb1e43e1e553dff106794841fd9a99ade2fc8c8bfce4d7872d";
// Without a network the embedded master, volume, filer and S3 gateway talk over loopback; the
// filer's unauthenticated HTTP API is disabled and volumes stay small enough for the memory cap.
const SEAWEEDFS_ARGS = ["server", "-ip=127.0.0.1", "-dir=/data", "-s3", "-s3.port=9000",
  "-filer.disableHttp", "-master.telemetry=false", "-master.volumeSizeLimitMB=64", "-volume.max=32"];
const LABEL = "com.nsangusa.operational-drill";
const MAX_ARCHIVE_BYTES = 16 * 1024 * 1024;

function require(condition, message) {
  if (!condition) throw new Error(message);
}

function run(args, { data, timeout = 90, check = true } = {}) {
  const result = spawnSync(args[0], args.slice(1), {
    cwd: ROOT, input: data, timeout: timeout * 1000, maxBuffer: 64 * 1024 * 1024
  });
  if (result.error) throw result.error;
  if (check && result.status) {
    throw new Error(`${args.slice(0, 3).join(",")} exited ${result.status}: ${result.stderr.toString().slice(-3000)}`);
  }
  return result;
}

const sleep = (milliseconds) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds);
const sha256 = (value) => createHash("sha256").update(value).digest("hex");
const hmac = (key, value) => createHmac("sha256", key).update(value).digest();
const shortId = () => randomUUID().replaceAll("-", "").slice(0, 12);
const sqlFile = (name) => readFileSync(path.join(ROOT, "infrastructure/scripts/sql", name), "utf8");
const sqlLiteral = (value) => JSON.stringify(value).replaceAll("'", "''");

// The archive is gzip-compressed JSON of base64 members plus a SHA-256 manifest.
export function packArchive(members) {
  const manifest = Object.fromEntries(Object.entries(members).map(([name, value]) => [name, sha256(value)]));
  const encoded = Object.fromEntries(Object.entries(members).map(([name, value]) => [name, value.toString("base64")]));
  return { manifest, archive: gzipSync(Buffer.from(JSON.stringify({ manifest, members: encoded }))) };
}

export function unpackArchive(archive) {
  const { manifest, members } = JSON.parse(gunzipSync(archive, { maxOutputLength: MAX_ARCHIVE_BYTES * 2 }).toString());
  const decoded = {};
  for (const [name, digest] of Object.entries(manifest)) {
    require(typeof members[name] === "string", `member missing: ${name}`);
    decoded[name] = Buffer.from(members[name], "base64");
    require(sha256(decoded[name]) === digest, `member integrity mismatch: ${name}`);
  }
  require(Object.keys(members).every((name) => Object.hasOwn(manifest, name)), "archive has unlisted members");
  return { manifest, members: decoded };
}

class Drill {
  constructor(output) {
    this.id = "nsangusa-ops-" + shortId();
    this.output = output;
    this.resources = [];
    this.evidence = {
      run: this.id, kind: "synthetic-logical-pg_dump-restore",
      productionPitr: false, approvedProductionPolicy: false, productionDeployment: false, providerCalls: 0,
      limits: {
        databaseMemoryMiB: 512, objectMemoryMiB: 256, objectClientMemoryMiB: 256,
        cpuPerService: 0.5, hostPorts: [], fixtureSources: 2, maxRowsPerLedger: 100,
        maxArchiveBytes: MAX_ARCHIVE_BYTES, maxObjectBytes: 4096, retentionBatch: 1
      },
      images: { postgres: POSTGRES, seaweedfs: SEAWEEDFS }, checks: {}, status: "running"
    };
    this.pg = this.id + "-pg";
    this.s3 = this.id + "-s3";
  }

  save() {
    writeFileSync(path.join(this.output, "evidence.json"), JSON.stringify(this.evidence, null, 2) + "\n");
  }

  check(name, condition) {
    require(condition, name);
    this.evidence.checks[name] = true;
    this.save();
  }

  docker(args, options) {
    return run(["docker", ...args], options);
  }

  createVolume(suffix) {
    const name = this.id + suffix;
    this.docker(["volume", "create", "--label", `${LABEL}=${this.id}`, name]);
    this.resources.push(["volume", name]);
    return name;
  }

  start() {
    for (const image of [POSTGRES, SEAWEEDFS]) {
      if (this.docker(["image", "inspect", image], { check: false }).status) this.docker(["pull", image], { timeout: 180 });
    }
    const pgVolume = this.createVolume("-pg-data");
    const s3Volume = this.createVolume("-s3-data");
    for (const [name, memory, volume, image, args] of [
      [this.pg, "512m", `${pgVolume}:/var/lib/postgresql`, POSTGRES,
        ["postgres", "-c", "shared_buffers=64MB", "-c", "max_connections=15",
          "-c", "statement_timeout=15000", "-c", "lock_timeout=5000"]],
      [this.s3, "256m", `${s3Volume}:/data`, SEAWEEDFS, SEAWEEDFS_ARGS]
    ]) {
      const env = name === this.pg
        ? ["-e", "POSTGRES_PASSWORD=synthetic-only", "-e", "POSTGRES_DB=source"]
        : ["-e", "AWS_ACCESS_KEY_ID=synthetic", "-e", "AWS_SECRET_ACCESS_KEY=synthetic-only"];
      this.docker(["create", "--name", name, "--label", `${LABEL}=${this.id}`,
        "--network", "none", "--memory", memory, "--memory-swap", memory,
        "--cpus", "0.5", "--pids-limit", "160", "-v", volume, ...env, image, ...args]);
      this.resources.push(["container", name]);
      this.docker(["start", name]);
    }
    // Probe over loopback TCP: the image's init server listens only on its socket and is ready
    // before the "source" database exists.
    this.waitFor("isolated PostgreSQL readiness timeout", () =>
      this.docker(["exec", this.pg, "pg_isready", "-h", "127.0.0.1", "-U", "postgres", "-d", "source"],
        { check: false }).status === 0);
    this.sql("select 1");
    this.waitFor("isolated object storage readiness timeout", () => this.s3Request("GET", "/healthz", null, false) !== null);
    this.s3Request("PUT", "/source");
    this.s3Request("PUT", "/restored");
    this.save();
  }

  waitFor(message, ready) {
    for (let attempt = 0; attempt < 45; attempt += 1) {
      if (ready()) return;
      sleep(1000);
    }
    throw new Error(message);
  }

  sql(statement, database = "source") {
    return this.docker(["exec", "-i", this.pg, "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1",
      "-U", "postgres", "-d", database], { data: Buffer.from(statement) }).stdout.toString().trim();
  }

  // One signed request from a throwaway client sharing only this owned container's isolated
  // network namespace. Returns the body, or null for a non-2xx when check is false.
  s3Request(method, objectPath, data = null, check = true) {
    require(objectPath.startsWith("/") && !objectPath.includes(".."), "unexpected object path");
    require(data === null || data.length <= 4096, "object exceeds fixture byte limit");
    const name = `${this.id}-s3-client-${shortId().slice(0, 8)}`;
    this.resources.push(["container", name]);
    const upload = data !== null ? ["--data-binary", "@-"] : [];
    const result = this.docker(["run", "--rm", ...(data !== null ? ["-i"] : []), "--name", name,
      "--label", `${LABEL}=${this.id}`, "--network", `container:${this.s3}`,
      "--memory", "256m", "--memory-swap", "256m", "--cpus", "0.5", "--pids-limit", "64",
      "--read-only", "--tmpfs", "/work:rw,noexec,nosuid,size=16m",
      "--entrypoint", "/bin/sh", SEAWEEDFS, "-c",
      'body=/work/body; status="$(curl -sS -o "$body" -w "%{http_code}" ' +
      '--aws-sigv4 aws:amz:us-east-1:s3 --user synthetic:synthetic-only "$@")" ' +
      '&& case "$status" in 2??) cat "$body" ;; ' +
      '*) echo "HTTP $status" >&2; head -c 512 "$body" >&2; exit 22 ;; esac',
      "s3", "-X", method, ...upload, `http://127.0.0.1:9000${objectPath}`], { data: data ?? undefined, check });
    return result.status === 0 ? result.stdout : null;
  }

  migrate() {
    const directory = path.join(ROOT, "backend/src/main/resources/db/migration");
    const files = readdirSync(directory).filter((name) => /^V\d+__.*\.sql$/.test(name))
      .sort((left, right) => Number(left.split("__")[0].slice(1)) - Number(right.split("__")[0].slice(1)));
    this.evidence.migrations = Object.fromEntries(files.map((name) => [name, sha256(readFileSync(path.join(directory, name)))]));
    require(files.at(-1).startsWith("V23__"), "expected operational V23 as latest migration");
    for (const name of files) this.sql("begin;\n" + readFileSync(path.join(directory, name), "utf8") + "\ncommit;");
    this.check("all_repository_migrations_applied_without_application_build", true);
  }

  fixture() {
    this.sql(sqlFile("operational-fixture.sql"));
    this.s3Request("PUT", "/source/retained.txt", Buffer.from("synthetic retained object\n"));
    this.s3Request("PUT", "/source/restricted.txt", Buffer.from("synthetic restricted object\n"));
    const metrics = readFileSync(path.join(ROOT,
      "backend/src/main/java/com/nsangusa/news/eventprocessing/internal/OperationalMetrics.java"), "utf8");
    const query = /static final String SQL\s*=\s*"""([\s\S]*?)"""/.exec(metrics)[1];
    const rows = this.sql(query).split("\n");
    this.check("actual_operational_metric_query_executes_against_migrated_schema", rows.length === 18);
  }

  inventory(database) {
    const queries = {};
    for (const table of ["articles", "source_posts", "outbox_events", "processed_events",
      "newsletter_deliveries", "source_tombstones"]) {
      queries[table] = sha256(this.sql(
        `select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), '[]') from ${table} t`, database));
    }
    return queries;
  }

  backup() {
    this.before = this.inventory("source");
    const database = this.docker(["exec", this.pg, "pg_dump", "-U", "postgres", "-Fc", "--no-owner", "source"]).stdout;
    const config = Buffer.from(JSON.stringify({
      consumers: "paused", ingress: false, migrations: this.evidence.migrations, providers: "fake"
    }));
    this.objectBytes = Object.fromEntries(["retained.txt", "restricted.txt"]
      .map((key) => [key, this.s3Request("GET", `/source/${key}`)]));
    const members = {
      "database.dump": database, "config.json": config,
      ...Object.fromEntries(Object.entries(this.objectBytes).map(([key, value]) => [`objects/${key}`, value]))
    };
    const { manifest, archive } = packArchive(members);
    require(archive.length <= MAX_ARCHIVE_BYTES, "archive exceeds bounded fixture limit");
    this.key = randomBytes(64);
    const keyfile = path.join(this.output, "synthetic-recovery.key");
    writeFileSync(keyfile, this.key.toString("hex"));
    chmodSync(keyfile, 0o600);
    const encrypted = run(["openssl", "enc", "-aes-256-cbc", "-pbkdf2", "-iter", "200000",
      "-salt", "-pass", `file:${keyfile}`], { data: archive }).stdout;
    writeFileSync(path.join(this.output, "backup.enc"), encrypted);
    writeFileSync(path.join(this.output, "backup.hmac"), hmac(this.key, encrypted).toString("hex"));
    this.evidence.backup = {
      sha256: sha256(encrypted), bytes: encrypted.length, manifest,
      archiveFormat: "gzip JSON members + SHA-256 manifest",
      encryption: "AES-256-CBC/PBKDF2-200000 + HMAC-SHA256",
      keyManagement: "synthetic key only; not a managed KMS"
    };
    this.backupTime = performance.now();
    this.save();
  }

  postBackupChanges() {
    this.sql(sqlFile("operational-post-backup.sql"));
    this.s3Request("DELETE", "/source/restricted.txt");
    this.ledger = {};
    for (const table of ["source_tombstones", "event_replay_suppressions", "newsletter_deliveries"]) {
      const rows = JSON.parse(this.sql(`select coalesce(jsonb_agg(to_jsonb(t)), '[]') from ${table} t`));
      require(rows.length <= 100, "ledger exceeds bounded fixture limit");
      this.ledger[table] = rows;
    }
    this.ledger.objectTombstones = ["restricted.txt"];
    writeFileSync(path.join(this.output, "synthetic-ledger.json"), JSON.stringify(this.ledger, null, 2));
  }

  restore() {
    const started = performance.now();
    const encrypted = readFileSync(path.join(this.output, "backup.enc"));
    const expected = Buffer.from(readFileSync(path.join(this.output, "backup.hmac"), "utf8"), "hex");
    const matches = (value) => expected.length === 32 && timingSafeEqual(hmac(this.key, value), expected);
    this.check("encrypted_backup_integrity", matches(encrypted));
    const tampered = Buffer.from(encrypted);
    tampered[tampered.length - 1] ^= 1;
    this.check("tampered_backup_rejected", !matches(tampered));
    const decrypted = run(["openssl", "enc", "-d", "-aes-256-cbc", "-pbkdf2", "-iter", "200000",
      "-pass", `file:${path.join(this.output, "synthetic-recovery.key")}`], { data: encrypted }).stdout;
    const { members } = unpackArchive(decrypted);
    this.sql("create database restored");
    this.docker(["exec", "-i", this.pg, "pg_restore", "-U", "postgres", "-d", "restored",
      "--exit-on-error", "--no-owner"], { data: members["database.dump"] });
    const restoredConfig = JSON.parse(members["config.json"].toString());
    this.check("config_restore_keeps_fake_providers_and_paused_consumers",
      restoredConfig.providers === "fake" && restoredConfig.consumers === "paused");
    for (const key of Object.keys(this.objectBytes)) this.s3Request("PUT", `/restored/${key}`, members[`objects/${key}`]);
    this.check("logical_restore_inventory_matches_snapshot",
      JSON.stringify(this.inventory("restored")) === JSON.stringify(this.before));
    const publicExecute = this.sql(
      "select count(*) from pg_proc p, lateral aclexplode(coalesce(p.proacl, acldefault('f',p.proowner))) a " +
      "where p.proname='apply_operational_retention' and a.grantee=0 and a.privilege_type='EXECUTE'", "restored");
    this.check("restore_preserves_retention_public_execute_revocation", publicExecute === "0");
    this.check("object_restore_integrity", Object.entries(this.objectBytes)
      .every(([key, value]) => this.s3Request("GET", `/restored/${key}`).equals(value)));
    this.evidence.restoreElapsedSeconds = Number(((performance.now() - started) / 1000).toFixed(3));
    this.evidence.logicalBackupAgeSeconds = Number(((performance.now() - this.backupTime) / 1000).toFixed(3));
    this.save();
  }

  applyLedger(ledger, prefix = "begin; ") {
    this.sql(prefix + "create temporary table recovery_input(document jsonb); " +
      `insert into recovery_input values ('${sqlLiteral(ledger)}'::jsonb);\n` +
      sqlFile("operational-recover.sql") + "\ncommit;", "restored");
  }

  rejectsLedger(ledger, expectedError) {
    try {
      this.applyLedger(ledger);
    } catch (error) {
      require(error.message.includes(expectedError), `unexpected negative recovery error: ${error.message}`);
      return true;
    }
    return false;
  }

  recover() {
    const before = JSON.stringify(this.inventory("restored"));
    const unknownSource = structuredClone(this.ledger);
    unknownSource.source_tombstones[0].source_post_id = "20000000-0000-0000-0000-000000000099";
    this.check("unknown_tombstone_fails_closed_without_partial_changes",
      this.rejectsLedger(unknownSource, "absent or changed source") && JSON.stringify(this.inventory("restored")) === before);
    const unknownCampaign = structuredClone(this.ledger);
    unknownCampaign.newsletter_deliveries[0].campaign_key = "unrecognized-campaign";
    this.check("unknown_newsletter_identity_blocks_recovery_without_partial_changes",
      this.rejectsLedger(unknownCampaign, "unknown delivery identities") && JSON.stringify(this.inventory("restored")) === before);
    this.applyLedger(this.ledger, "begin; set local statement_timeout='15s'; ");
    for (const key of this.ledger.objectTombstones) {
      require(key === "restricted.txt", "unexpected object key; refusing broad deletion");
      this.s3Request("DELETE", `/restored/${key}`);
    }
    this.check("retained_object_survives_deletion_replay",
      this.s3Request("GET", "/restored/retained.txt").equals(this.objectBytes["retained.txt"]));
    const remaining = this.s3Request("GET", "/restored?list-type=2").toString();
    this.check("restricted_object_not_resurrected", !remaining.includes("restricted.txt"));
    this.sql(sqlFile("operational-assertions.sql"), "restored");
    this.check("deletion_suppression_inbox_outbox_newsletter_reconciled", true);
    // Committed after the backup: present in the source, absent from the recovery point.
    const lost = JSON.parse(this.sql("select coalesce(jsonb_agg(id::text), '[]') from outbox_events " +
      "where idempotency_key='synthetic-after-backup'"));
    this.evidence.recoveryPointMissingOutboxIds = lost;
    this.evidence.lostCommittedEventsRecreated = 0;
    this.check("post_backup_event_loss_explicitly_reported_not_fabricated", lost.length === 1);
    const first = JSON.stringify(this.inventory("restored"));
    this.applyLedger(this.ledger);
    this.check("recovery_replay_is_idempotent", JSON.stringify(this.inventory("restored")) === first);
    this.sql(sqlFile("operational-retention-test.sql"), "restored");
    this.check("retention_fail_closed_bounded_dry_run_and_legal_hold", true);
    // Old application shape remains readable/writable after additive V23; this is not a binary rollout.
    this.sql("begin; select id,event_type,aggregate_id,envelope_json,published_at " +
      "from outbox_events order by created_at limit 1; " +
      "update articles set version=version+1 where slug='retained-synthetic'; rollback;", "restored");
    this.check("old_schema_contract_transaction_after_additive_migration", true);
    this.evidence.rollbackScope = "SQL old-column contract only; no old/new application binary rollout";
    this.evidence.localLimitations = [
      "Application binary rollback/forward deployment not performed by this SQL-only drill",
      "No Kafka broker replay or SMTP delivery; downstream services remain absent/paused"];
    this.evidence.externalBlockers = [
      "Managed PostgreSQL encrypted PITR/failover and approved RPO/RTO",
      "Object versioning, KMS, offsite immutable backup and provider deletion attestations",
      "Approved per-store retention and hold policy; fixture reference is explicitly synthetic",
      "External Kafka retained history and SMTP outcomes after the recovery point",
      "Production traffic envelope, ACL/TLS/secret rotation, alert routing and cluster admission"];
    this.evidence.status = "passed";
    this.save();
  }

  cleanup() {
    const failures = [];
    for (const [kind, name] of [...this.resources].reverse()) {
      const inspected = this.docker([kind, "inspect", name], { check: false });
      if (inspected.status) {
        const detail = inspected.stderr.toString().toLowerCase();
        if (!["no such object", "no such container", "no such volume"].some((text) => detail.includes(text))) {
          failures.push(`could not verify cleanup ownership/existence: ${name}`);
        }
        continue;
      }
      const metadata = JSON.parse(inspected.stdout)[0];
      const labels = kind === "volume" ? metadata.Labels : metadata.Config?.Labels;
      if (labels?.[LABEL] !== this.id) {
        failures.push(`refused non-owned ${kind}: ${name}`);
        continue;
      }
      const command = kind === "volume" ? ["volume", "rm", name] : ["rm", "-f", name];
      if (this.docker(command, { check: false }).status) failures.push(`cleanup failed: ${name}`);
    }
    this.evidence.cleanup = { ownedResourcesOnly: true, errors: failures };
    if (failures.length) {
      this.evidence.status = "failed";
      this.evidence.failure = failures.join("; ");
    }
    this.save();
    require(!failures.length, failures.join("; "));
  }
}

function main() {
  const { values } = parseArgs({
    options: { output: { type: "string" }, "keep-on-failure": { type: "boolean", default: false } }
  });
  require(values.output, "--output is required: a new evidence directory below the repository");
  process.umask(0o077);
  const output = path.resolve(ROOT, values.output);
  require(output.startsWith(ROOT + path.sep), "evidence must be below repository");
  require(!existsSync(output), "refusing to overwrite existing evidence");
  mkdirSync(output, { recursive: true });
  const drill = new Drill(output);
  try {
    drill.start();
    drill.migrate();
    drill.fixture();
    drill.backup();
    drill.postBackupChanges();
    drill.restore();
    drill.recover();
  } catch (error) {
    drill.evidence.status = "failed";
    drill.evidence.failure = error.message;
    drill.evidence.ownedResources = drill.resources;
    for (const container of [drill.pg, drill.s3]) {
      const logs = drill.docker(["logs", "--tail", "100", container], { check: false });
      writeFileSync(path.join(output, container + ".log"), Buffer.concat([logs.stdout, logs.stderr]));
    }
    drill.save();
    if (values["keep-on-failure"]) console.error(`Failed resources retained: ${JSON.stringify(drill.resources)}`);
    else drill.cleanup();
    throw error;
  }
  drill.cleanup();
  console.log(JSON.stringify({ status: "passed", evidence: path.join(output, "evidence.json") }));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
