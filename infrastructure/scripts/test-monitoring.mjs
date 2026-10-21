#!/usr/bin/env node
// Render opt-in monitoring safely and exercise real Prometheus expressions without a cluster.
import { spawnSync } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const PROMETHEUS = "prom/prometheus@sha256:63805ebb8d2b3920190daf1cb14a60871b16fd38bed42b857a3182bc621f4996";
const CHART = "infrastructure/helm/nsangusa";

function check(condition, message) {
  if (!condition) throw new Error(message);
}

function run(command, args, timeout) {
  const result = spawnSync(command, args, { cwd: ROOT, encoding: "utf8", timeout, maxBuffer: 64 * 1024 * 1024 });
  if (result.error) throw result.error;
  return result;
}

function findOnPath(name) {
  for (const directory of (process.env.PATH || "").split(path.delimiter)) {
    const candidate = path.join(directory, name);
    if (directory && existsSync(candidate)) return candidate;
  }
  return undefined;
}

function render(helm, args = [], succeeds = true) {
  const result = run(helm, ["template", "operations-test", CHART, ...args], 30_000);
  check((result.status === 0) === succeeds, result.stderr || "unexpected render result");
  return result.stdout + result.stderr;
}

function qualify(evidence) {
  const helm = process.env.HELM || findOnPath("helm");
  check(helm, "Supply HELM=/path/to/existing/helm or install the repository-pinned Helm tool");
  const fallback = render(helm);
  check(!fallback.includes("kind: ServiceMonitor") && !fallback.includes("backend-metrics"), "monitoring must be opt-in");
  const enabled = ["--set", "monitoring.enabled=true"];
  const plain = render(helm, enabled);
  check(!plain.includes("kind: ServiceMonitor") && !plain.includes("kind: PrometheusRule"),
    "default monitoring must not assume CRDs");
  check(plain.includes('MANAGEMENT_SERVER_PORT: "8081"') && plain.includes("port: management"),
    "management listener/probe wiring missing");
  check(plain.includes("nsangusa-rules.yaml:") && plain.includes("nsangusa-operations.json:"),
    "plain ConfigMap resources missing");
  const linked = render(helm, [...enabled, "--set", "monitoring.runbookUrl=https://operations.example.invalid/reliability"]);
  check(linked.includes("https://operations.example.invalid/reliability#outbox-lag-or-kafka-outage"),
    "deployment-specific runbook links not wired");
  for (const [option, expected] of [
    ["monitoring.serviceMonitor.enabled=true", "ServiceMonitor CRD absent"],
    ["monitoring.prometheusRule.enabled=true", "PrometheusRule CRD absent"],
    ["networkPolicy.enabled=false", "requires NetworkPolicy"],
    ["monitoring.port=8080", "8081"]
  ]) {
    check(render(helm, [...enabled, "--set", option], false).includes(expected), `missing fail-closed guard: ${option}`);
  }
  for (const [crd, setting] of [["ServiceMonitor", "serviceMonitor"], ["PrometheusRule", "prometheusRule"]]) {
    const rendered = render(helm, [...enabled, "--set", `monitoring.${setting}.enabled=true`,
      "--api-versions", `monitoring.coreos.com/v1/${crd}`]);
    check(rendered.includes(`kind: ${crd}`), `qualified ${crd} not rendered`);
  }
  const dashboard = JSON.parse(readFileSync(path.join(ROOT, CHART, "files/observability/dashboard.json"), "utf8"));
  check(dashboard.panels.length >= 10, "operational dashboard incomplete");
  evidence.helmSafetyChecks = 12;
  const name = "nsangusa-alert-test-" + randomUUID().replaceAll("-", "").slice(0, 12);
  const state = path.join(ROOT, ".local/operational-alert-state", name);
  mkdirSync(state, { recursive: true, mode: 0o700 });
  try {
    const result = run("docker", ["run", "--rm", "--name", name, "--network", "none", "--read-only",
      "--memory", "256m", "--memory-swap", "256m", "--cpus", "0.5", "--pids-limit", "64",
      "--user", `${process.getuid()}:${process.getgid()}`, "-e", "TMPDIR=/state",
      "--label", `com.nsangusa.operational-alert-test=${name}`,
      "-v", `${state}:/state`, "-v", `${path.join(ROOT, "infrastructure")}:/work:ro`,
      "-w", "/work/observability",
      "--entrypoint", "/bin/promtool", PROMETHEUS, "test", "rules", "alert-tests.json"], 120_000);
    evidence.promtoolOutput = result.stdout + result.stderr;
    check(result.status === 0, "Prometheus rule tests failed; see evidence");
  } finally {
    const inspected = run("docker", ["inspect", name], 30_000);
    if (inspected.status === 0) {
      const labels = JSON.parse(inspected.stdout)[0].Config.Labels;
      check(labels?.["com.nsangusa.operational-alert-test"] === name,
        "refusing to clean a container without this test's ownership label");
      check(run("docker", ["rm", "-f", name], 30_000).status === 0, "could not remove test container");
    } else {
      const detail = inspected.stderr.toLowerCase();
      check(detail.includes("no such object") || detail.includes("no such container"),
        "could not verify test container cleanup");
    }
    rmSync(state, { recursive: true, force: true });
  }
}

function main() {
  const { values } = parseArgs({ options: { output: { type: "string" } } });
  process.umask(0o077);
  const output = path.resolve(ROOT, values.output
    || `.local/operational-evidence/monitoring-${randomUUID().replaceAll("-", "").slice(0, 12)}.json`);
  check(output.startsWith(ROOT + path.sep) && !existsSync(output), "evidence must be a new path below repository");
  mkdirSync(path.dirname(output), { recursive: true });
  const asset = (name) => createHash("sha256")
    .update(readFileSync(path.join(ROOT, CHART, "files/observability", name))).digest("hex");
  const evidence = {
    status: "running", prometheusImage: PROMETHEUS, productionAlertRouting: "not-qualified",
    limits: { memoryMiB: 256, cpus: 0.5, hostPorts: [], network: "none" },
    assets: { "rules.json": asset("rules.json"), "dashboard.json": asset("dashboard.json") },
    promtoolCases: JSON.parse(readFileSync(path.join(ROOT, "infrastructure/observability/alert-tests.json"), "utf8")).tests.length
  };
  try {
    qualify(evidence);
    evidence.status = "passed";
  } catch (error) {
    evidence.status = "failed";
    evidence.failure = error.message;
    throw error;
  } finally {
    writeFileSync(output, JSON.stringify(evidence, null, 2) + "\n");
    console.log(JSON.stringify({ status: evidence.status, evidence: output }));
  }
}

try {
  main();
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
