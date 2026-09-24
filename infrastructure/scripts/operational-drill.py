#!/usr/bin/env python3
"""Bounded synthetic logical restore drill. Never connects to an operator database or host port."""
import argparse
import hashlib
import hmac
import io
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
POSTGRES = "postgres@sha256:a02db8cac496f15b094798a38254f14d6e00741f709360e5e00bb6668ea31636"
MINIO = "quay.io/minio/minio@sha256:a1ea29fa28355559ef137d71fc570e508a214ec84ff8083e39bc5428980b015e"
MC = "quay.io/minio/mc@sha256:09f93f534cde415d192bb6084dd0e0ddd1715fb602f8a922ad121fd2bf0f8b44"
LABEL = "com.nsangusa.operational-drill"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def run(args, data=None, timeout=90, check=True):
    result = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=timeout, cwd=ROOT)
    if check and result.returncode:
        raise RuntimeError(f"{args[0:3]} exited {result.returncode}: {result.stderr.decode()[-3000:]}")
    return result


class Drill:
    def __init__(self, output):
        self.id = "nsangusa-ops-" + uuid.uuid4().hex[:12]
        self.output = output
        self.resources = []
        self.evidence = {
            "run": self.id, "kind": "synthetic-logical-pg_dump-restore",
            "productionPitr": False, "approvedProductionPolicy": False,
            "productionDeployment": False, "providerCalls": 0,
            "limits": {"databaseMemoryMiB": 512, "objectMemoryMiB": 256,
                       "objectClientMemoryMiB": 256,
                       "cpuPerService": 0.5, "hostPorts": [], "fixtureSources": 2,
                       "maxRowsPerLedger": 100, "maxArchiveBytes": 16777216,
                       "maxObjectBytes": 4096,
                       "retentionBatch": 1},
            "images": {"postgres": POSTGRES, "minio": MINIO, "mc": MC},
            "checks": {}, "status": "running"}
        self.pg = self.id + "-pg"
        self.s3 = self.id + "-s3"

    def save(self):
        (self.output / "evidence.json").write_text(json.dumps(self.evidence, indent=2) + "\n")

    def check(self, name, condition):
        require(condition, name)
        self.evidence["checks"][name] = True
        self.save()

    def docker(self, *args, **kwargs):
        return run(["docker", *args], **kwargs)

    def create_volume(self, suffix):
        name = self.id + suffix
        self.docker("volume", "create", "--label", f"{LABEL}={self.id}", name)
        self.resources.append(("volume", name))
        return name

    def start(self):
        for image in (POSTGRES, MINIO, MC):
            if self.docker("image", "inspect", image, check=False).returncode:
                self.docker("pull", image, timeout=180)
        pg_volume = self.create_volume("-pg-data")
        s3_volume = self.create_volume("-s3-data")
        for name, memory, volume, image, args in [
            (self.pg, "512m", f"{pg_volume}:/var/lib/postgresql", POSTGRES,
             ["postgres", "-c", "shared_buffers=64MB", "-c", "max_connections=15",
              "-c", "statement_timeout=15000", "-c", "lock_timeout=5000"]),
            (self.s3, "256m", f"{s3_volume}:/data", MINIO, ["server", "/data"])
        ]:
            env = (["-e", "POSTGRES_PASSWORD=synthetic-only", "-e", "POSTGRES_DB=source"]
                   if name == self.pg else
                   ["-e", "MINIO_ROOT_USER=synthetic", "-e", "MINIO_ROOT_PASSWORD=synthetic-only"])
            self.docker("create", "--name", name, "--label", f"{LABEL}={self.id}",
                        "--network", "none", "--memory", memory, "--memory-swap", memory,
                        "--cpus", "0.5", "--pids-limit", "160",
                        "-v", volume, *env, image, *args)
            self.resources.append(("container", name))
            self.docker("start", name)
        for attempt in range(45):
            if self.docker("exec", self.pg, "pg_isready", "-U", "postgres", "-d", "source",
                           check=False).returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("isolated PostgreSQL readiness timeout")
        self.sql("select 1")
        self.mc(["mb", "local/source"])
        self.mc(["mb", "local/restored"])
        self.save()

    def sql(self, statement, database="source"):
        return self.docker("exec", "-i", self.pg, "psql", "-X", "-qAt", "-v",
                           "ON_ERROR_STOP=1", "-U", "postgres", "-d", database,
                           data=statement.encode()).stdout.decode().strip()

    def mc(self, args, data=None):
        # The helper shares only this owned container's isolated network namespace.
        name = self.id + "-mc-" + uuid.uuid4().hex[:8]
        self.resources.append(("container", name))
        upload = None
        mounts = []
        try:
            if args[0] == "pipe":
                require(data is not None and len(data) <= 4096, "object exceeds fixture byte limit")
                upload = self.output / (name + ".object")
                with upload.open("xb") as destination:
                    destination.write(data)
                mounts = ["-v", f"{upload}:/input/object:ro"]
                # Unknown-size stdin uploads preallocate large multipart buffers in this client.
                args = ["cp", "--disable-multipart", "/input/object", args[1]]
                data = None
            result = self.docker(
                "run", "--rm", "--name", name, "--label", f"{LABEL}={self.id}",
                "--network", f"container:{self.s3}",
                "--memory", "256m", "--memory-swap", "256m", "--cpus", "0.5", "--pids-limit", "64",
                "--read-only", "--tmpfs", "/work:rw,noexec,nosuid,size=16m", *mounts,
                "-e", "MC_CONFIG_DIR=/work", "-e", "GOMEMLIMIT=192MiB", "-e", "GOMAXPROCS=2",
                "--entrypoint", "/bin/sh", "-i", MC, "-c",
                'mc alias set local http://127.0.0.1:9000 synthetic synthetic-only >/dev/null && exec mc "$@"',
                "mc", *args, data=data)
            return result.stdout
        finally:
            if upload is not None:
                upload.unlink()

    def migrate(self):
        files = sorted((ROOT / "backend/src/main/resources/db/migration").glob("V*.sql"),
                       key=lambda path: int(path.name.split("__")[0][1:]))
        self.evidence["migrations"] = {
            path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in files}
        require(files[-1].name.startswith("V23__"), "expected operational V23 as latest migration")
        for path in files:
            self.sql("begin;\n" + path.read_text() + "\ncommit;")
        self.check("all_repository_migrations_applied_without_application_build", True)

    def fixture(self):
        self.sql((ROOT / "infrastructure/scripts/sql/operational-fixture.sql").read_text())
        self.mc(["pipe", "local/source/retained.txt"], b"synthetic retained object\n")
        self.mc(["pipe", "local/source/restricted.txt"], b"synthetic restricted object\n")
        metrics = (ROOT / "backend/src/main/java/com/nsangusa/news/eventprocessing/internal/OperationalMetrics.java").read_text()
        query = re.search(r'static final String SQL\s*=\s*"""(.*?)"""', metrics, re.S).group(1)
        rows = self.sql(query).splitlines()
        self.check("actual_operational_metric_query_executes_against_migrated_schema", len(rows) == 18)

    def inventory(self, database):
        queries = {}
        for table in ("articles", "source_posts", "outbox_events", "processed_events",
                      "newsletter_deliveries", "source_tombstones"):
            text = self.sql(
                f"select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), '[]') "
                f"from {table} t", database)
            queries[table] = hashlib.sha256(text.encode()).hexdigest()
        return queries

    def backup(self):
        self.before = self.inventory("source")
        database = self.docker("exec", self.pg, "pg_dump", "-U", "postgres", "-Fc",
                               "--no-owner", "source").stdout
        config = json.dumps({"providers": "fake", "ingress": False, "consumers": "paused",
                             "migrations": self.evidence["migrations"]}, sort_keys=True).encode()
        bundle = io.BytesIO()
        self.object_bytes = {key: self.mc(["cat", f"local/source/{key}"])
                             for key in ("retained.txt", "restricted.txt")}
        members = {"database.dump": database, "config.json": config,
                   **{f"objects/{key}": value for key, value in self.object_bytes.items()}}
        manifest = {key: hashlib.sha256(value).hexdigest() for key, value in members.items()}
        with zipfile.ZipFile(bundle, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for key, value in members.items():
                archive.writestr(key, value)
            archive.writestr("manifest.json", json.dumps(manifest, sort_keys=True))
        require(len(bundle.getvalue()) <= 16777216, "archive exceeds bounded fixture limit")
        self.key = secrets.token_bytes(64)
        keyfile = self.output / "synthetic-recovery.key"
        keyfile.write_bytes(self.key.hex().encode())
        keyfile.chmod(0o600)
        encrypted = run(["openssl", "enc", "-aes-256-cbc", "-pbkdf2", "-iter", "200000",
                         "-salt", "-pass", f"file:{keyfile}"], data=bundle.getvalue()).stdout
        (self.output / "backup.enc").write_bytes(encrypted)
        tag = hmac.new(self.key, encrypted, hashlib.sha256).hexdigest()
        (self.output / "backup.hmac").write_text(tag)
        self.evidence["backup"] = {"sha256": hashlib.sha256(encrypted).hexdigest(),
                                   "bytes": len(encrypted), "manifest": manifest,
                                   "encryption": "AES-256-CBC/PBKDF2-200000 + HMAC-SHA256",
                                   "keyManagement": "synthetic key only; not a managed KMS"}
        self.backup_time = time.monotonic()
        self.save()

    def post_backup_changes(self):
        self.sql((ROOT / "infrastructure/scripts/sql/operational-post-backup.sql").read_text())
        self.mc(["rm", "local/source/restricted.txt"])
        self.ledger = {}
        for table in ("source_tombstones", "event_replay_suppressions", "newsletter_deliveries"):
            rows = json.loads(self.sql(
                f"select coalesce(jsonb_agg(to_jsonb(t)), '[]') from {table} t"))
            require(len(rows) <= 100, "ledger exceeds bounded fixture limit")
            self.ledger[table] = rows
        self.ledger["objectTombstones"] = ["restricted.txt"]
        (self.output / "synthetic-ledger.json").write_text(json.dumps(self.ledger, indent=2))

    def restore(self):
        started = time.monotonic()
        encrypted = (self.output / "backup.enc").read_bytes()
        expected = (self.output / "backup.hmac").read_text()
        self.check("encrypted_backup_integrity",
                   hmac.compare_digest(hmac.new(self.key, encrypted, hashlib.sha256).hexdigest(), expected))
        tampered = bytearray(encrypted)
        tampered[-1] ^= 1
        self.check("tampered_backup_rejected",
                   not hmac.compare_digest(hmac.new(self.key, tampered, hashlib.sha256).hexdigest(), expected))
        decrypted = run(["openssl", "enc", "-d", "-aes-256-cbc", "-pbkdf2", "-iter", "200000",
                         "-pass", f"file:{self.output / 'synthetic-recovery.key'}"],
                        data=encrypted).stdout
        with zipfile.ZipFile(io.BytesIO(decrypted)) as archive:
            manifest = json.loads(archive.read("manifest.json"))
            for name, digest in manifest.items():
                require(hashlib.sha256(archive.read(name)).hexdigest() == digest,
                        f"member integrity mismatch: {name}")
            self.sql("create database restored")
            self.docker("exec", "-i", self.pg, "pg_restore", "-U", "postgres", "-d", "restored",
                        "--exit-on-error", "--no-owner", data=archive.read("database.dump"))
            restored_config = json.loads(archive.read("config.json"))
            self.check("config_restore_keeps_fake_providers_and_paused_consumers",
                       restored_config["providers"] == "fake" and restored_config["consumers"] == "paused")
            for key in self.object_bytes:
                self.mc(["pipe", f"local/restored/{key}"], archive.read(f"objects/{key}"))
        self.check("logical_restore_inventory_matches_snapshot", self.inventory("restored") == self.before)
        public_execute = self.sql(
            "select count(*) from pg_proc p, lateral aclexplode(coalesce(p.proacl, acldefault('f',p.proowner))) a "
            "where p.proname='apply_operational_retention' and a.grantee=0 and a.privilege_type='EXECUTE'",
            "restored")
        self.check("restore_preserves_retention_public_execute_revocation", public_execute == "0")
        self.check("object_restore_integrity",
                   all(self.mc(["cat", f"local/restored/{key}"]) == data
                       for key, data in self.object_bytes.items()))
        self.evidence["restoreElapsedSeconds"] = round(time.monotonic() - started, 3)
        self.evidence["logicalBackupAgeSeconds"] = round(time.monotonic() - self.backup_time, 3)
        self.save()

    def recover(self):
        ledger = json.dumps(self.ledger).replace("'", "''")
        sql = (ROOT / "infrastructure/scripts/sql/operational-recover.sql").read_text()
        before = self.inventory("restored")
        invalid = json.loads(json.dumps(self.ledger))
        invalid["source_tombstones"][0]["source_post_id"] = "20000000-0000-0000-0000-000000000099"
        rejected = False
        try:
            encoded = json.dumps(invalid).replace("'", "''")
            self.sql("begin; create temporary table recovery_input(document jsonb); "
                     f"insert into recovery_input values ('{encoded}'::jsonb);\n" + sql + "\ncommit;",
                     "restored")
        except RuntimeError as error:
            require("absent or changed source" in str(error), "unexpected negative recovery error")
            rejected = True
        self.check("unknown_tombstone_fails_closed_without_partial_changes",
                   rejected and self.inventory("restored") == before)
        invalid = json.loads(json.dumps(self.ledger))
        invalid["newsletter_deliveries"][0]["campaign_key"] = "unrecognized-campaign"
        rejected = False
        try:
            encoded = json.dumps(invalid).replace("'", "''")
            self.sql("begin; create temporary table recovery_input(document jsonb); "
                     f"insert into recovery_input values ('{encoded}'::jsonb);\n" + sql + "\ncommit;",
                     "restored")
        except RuntimeError as error:
            require("unknown delivery identities" in str(error), "unexpected newsletter recovery error")
            rejected = True
        self.check("unknown_newsletter_identity_blocks_recovery_without_partial_changes",
                   rejected and self.inventory("restored") == before)
        self.sql("begin; set local statement_timeout='15s'; "
                 "create temporary table recovery_input(document jsonb); "
                 f"insert into recovery_input values ('{ledger}'::jsonb);\n" + sql + "\ncommit;", "restored")
        for key in self.ledger["objectTombstones"]:
            require(key == "restricted.txt", "unexpected object key; refusing broad deletion")
            self.mc(["rm", f"local/restored/{key}"])
        self.check("retained_object_survives_deletion_replay",
                   self.mc(["cat", "local/restored/retained.txt"]) == self.object_bytes["retained.txt"])
        remaining = self.mc(["ls", "--json", "local/restored"]).decode()
        self.check("restricted_object_not_resurrected", "restricted.txt" not in remaining)
        self.sql((ROOT / "infrastructure/scripts/sql/operational-assertions.sql").read_text(), "restored")
        self.check("deletion_suppression_inbox_outbox_newsletter_reconciled", True)
        lost = json.loads(self.sql(
            "select coalesce(jsonb_agg(id::text), '[]') from outbox_events "
            "where idempotency_key='synthetic-after-backup'"))
        self.evidence["recoveryPointMissingOutboxIds"] = lost
        self.evidence["lostCommittedEventsRecreated"] = 0
        self.check("post_backup_event_loss_explicitly_reported_not_fabricated", len(lost) == 1)
        first = self.inventory("restored")
        self.sql("begin; create temporary table recovery_input(document jsonb); "
                 f"insert into recovery_input values ('{ledger}'::jsonb);\n" + sql + "\ncommit;", "restored")
        self.check("recovery_replay_is_idempotent", self.inventory("restored") == first)
        self.sql((ROOT / "infrastructure/scripts/sql/operational-retention-test.sql").read_text(), "restored")
        self.check("retention_fail_closed_bounded_dry_run_and_legal_hold", True)
        # Old application shape remains readable/writable after additive V23; this is not a binary rollout.
        self.sql("begin; select id,event_type,aggregate_id,envelope_json,published_at "
                 "from outbox_events order by created_at limit 1; "
                 "update articles set version=version+1 where slug='retained-synthetic'; rollback;",
                 "restored")
        self.check("old_schema_contract_transaction_after_additive_migration", True)
        self.evidence["rollbackScope"] = "SQL old-column contract only; no old/new application binary rollout"
        self.evidence["localLimitations"] = [
            "Application binary rollback/forward deployment not performed by this SQL-only drill",
            "No Kafka broker replay or SMTP delivery; downstream services remain absent/paused"]
        self.evidence["externalBlockers"] = [
            "Managed PostgreSQL encrypted PITR/failover and approved RPO/RTO",
            "Object versioning, KMS, offsite immutable backup and provider deletion attestations",
            "Approved per-store retention and hold policy; fixture reference is explicitly synthetic",
            "External Kafka retained history and SMTP outcomes after the recovery point",
            "Production traffic envelope, ACL/TLS/secret rotation, alert routing and cluster admission"]
        self.evidence["status"] = "passed"
        self.save()

    def cleanup(self):
        failures = []
        for kind, name in reversed(self.resources):
            inspected = self.docker(kind, "inspect", name, check=False)
            if inspected.returncode:
                detail = inspected.stderr.decode().lower()
                if not any(text in detail for text in ("no such object", "no such container", "no such volume")):
                    failures.append(f"could not verify cleanup ownership/existence: {name}")
                continue
            metadata = json.loads(inspected.stdout)[0]
            labels = metadata.get("Labels") if kind == "volume" else metadata.get("Config", {}).get("Labels")
            if (labels or {}).get(LABEL) != self.id:
                failures.append(f"refused non-owned {kind}: {name}")
                continue
            command = ["volume", "rm", name] if kind == "volume" else ["rm", "-f", name]
            if self.docker(*command, check=False).returncode:
                failures.append(f"cleanup failed: {name}")
        self.evidence["cleanup"] = {"ownedResourcesOnly": True, "errors": failures}
        if failures:
            self.evidence["status"] = "failed"
            self.evidence["failure"] = "; ".join(failures)
        self.save()
        require(not failures, "; ".join(failures))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, help="New evidence directory below the repository")
    parser.add_argument("--keep-on-failure", action="store_true",
                        help="Keep only labelled failed drill resources for inspection")
    args = parser.parse_args()
    os.umask(0o077)
    output = (ROOT / args.output).resolve()
    require(output.is_relative_to(ROOT) and output != ROOT, "evidence must be below repository")
    require(not output.exists(), "refusing to overwrite existing evidence")
    output.mkdir(parents=True)
    drill = Drill(output)
    try:
        drill.start()
        drill.migrate()
        drill.fixture()
        drill.backup()
        drill.post_backup_changes()
        drill.restore()
        drill.recover()
    except Exception as error:
        drill.evidence["status"] = "failed"
        drill.evidence["failure"] = str(error)
        drill.evidence["ownedResources"] = drill.resources
        for container in (drill.pg, drill.s3):
            logs = drill.docker("logs", "--tail", "100", container, check=False)
            (output / (container + ".log")).write_bytes(logs.stdout + logs.stderr)
        drill.save()
        if args.keep_on_failure:
            print(f"Failed resources retained: {drill.resources}", file=sys.stderr)
        else:
            drill.cleanup()
        raise
    else:
        drill.cleanup()
        print(json.dumps({"status": "passed", "evidence": str(output / "evidence.json")}))


if __name__ == "__main__":
    main()
