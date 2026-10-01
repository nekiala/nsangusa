#!/usr/bin/env node
// Record or compare a non-secret, read-only baseline of workloads sharing a deployment host.
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { lstatSync, readdirSync, readFileSync, readlinkSync, realpathSync, statSync, writeFileSync } from "node:fs";
import http from "node:http";
import https from "node:https";
import { hostname } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { isDeepStrictEqual, parseArgs } from "node:util";

export const CONTAINER_FORMAT =
  '{"id":{{json .Id}},"image":{{json .Image}},' +
  '"startedAt":{{json .State.StartedAt}},"restartCount":{{.RestartCount}},' +
  '"health":{{json .State.Health.Status}}}';
const KEY_SUFFIXES = new Set([".key", ".pem", ".crt"]);

export function command(...args) {
  return execFileSync(args[0], args.slice(1), { encoding: "utf8", timeout: 30_000, stdio: ["ignore", "pipe", "pipe"] });
}

export async function smoke(url) {
  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    throw new TypeError("Use a loopback HTTP(S) smoke URL without credentials or query data");
  }
  if (!["http:", "https:"].includes(parsed.protocol)
      || !["127.0.0.1", "[::1]", "localhost"].includes(parsed.hostname)
      || parsed.username || parsed.password || parsed.search || parsed.hash || url.includes("#") || url.includes("?")) {
    throw new TypeError("Use a loopback HTTP(S) smoke URL without credentials or query data");
  }
  // node:http never follows redirects and ignores proxy environment variables.
  const client = parsed.protocol === "https:" ? https : http;
  const status = await new Promise((resolve, reject) => {
    const request = client.get(parsed, { agent: false, timeout: 10_000 }, (response) => {
      response.resume();
      resolve(response.statusCode);
    });
    request.on("timeout", () => request.destroy(new Error("Protected workload smoke timed out")));
    request.on("error", reject);
  });
  if (status >= 300 && status < 400) throw new Error("The protected workload smoke must not redirect");
  if (status !== 200) throw new Error(`Protected workload returned HTTP ${status}`);
  return { url, status: 200 };
}

function walk(directory) {
  // Like a recursive glob: symlinked directories are listed but not descended into.
  const entries = [];
  for (const name of readdirSync(directory).sort()) {
    const entry = path.join(directory, name);
    entries.push(entry);
    if (lstatSync(entry).isDirectory()) entries.push(...walk(entry));
  }
  return entries;
}

function isDirectory(entry) {
  try {
    return statSync(entry).isDirectory();
  } catch {
    return false;
  }
}

function isFile(entry) {
  try {
    return statSync(entry).isFile();
  } catch {
    return false;
  }
}

export async function capture(containers, units, configDirs, url, deps = {}) {
  const run = deps.command || command;
  const result = {
    schemaVersion: 2, hostname: hostname(), containers: {}, units: {},
    files: {}, symlinks: {}, smoke: await (deps.smoke || smoke)(url)
  };
  for (const name of containers) {
    if (!name || name.startsWith("-")) throw new TypeError("Invalid container name");
    const state = JSON.parse(run("docker", "--host", "unix:///var/run/docker.sock",
      "inspect", "--format", CONTAINER_FORMAT, name));
    if (state.health !== "healthy") throw new Error(`Protected container is not healthy: ${name}`);
    result.containers[name] = state;
  }
  for (const name of units) {
    if (!name.endsWith(".service") || name.startsWith("-")) throw new TypeError("Use explicit .service unit names");
    const output = run("systemctl", "show", name, "--property=ActiveState",
      "--property=MainPID", "--property=ActiveEnterTimestampMonotonic");
    const state = Object.fromEntries(output.split("\n").filter(Boolean).map((line) => {
      const index = line.indexOf("=");
      return [line.slice(0, index), line.slice(index + 1)];
    }));
    if (state.ActiveState !== "active" || !(Number.parseInt(state.MainPID, 10) > 0)) {
      throw new Error(`Protected service is not running: ${name}`);
    }
    result.units[name] = state;
  }
  const explicitDirectories = new Set(configDirs.map((directory) => realpathSync(directory)));
  for (const directory of configDirs) {
    if (!path.isAbsolute(directory) || !isDirectory(directory)) {
      throw new TypeError("Configuration directories must be existing absolute paths");
    }
    for (const entry of walk(directory)) {
      if (lstatSync(entry).isSymbolicLink()) {
        result.symlinks[entry] = readlinkSync(entry);
        if (isDirectory(entry) && !explicitDirectories.has(realpathSync(entry))) {
          throw new TypeError("Include linked configuration directory targets explicitly");
        }
      }
      if (!isFile(entry)) continue;
      if (KEY_SUFFIXES.has(path.extname(entry)) || KEY_SUFFIXES.has(path.extname(realpathSync(entry)))) continue;
      result.files[entry] = createHash("sha256").update(readFileSync(entry)).digest("hex");
    }
  }
  if (!Object.keys(result.containers).length || !Object.keys(result.units).length || !Object.keys(result.files).length) {
    throw new TypeError("A preservation baseline requires containers, services and config files");
  }
  return result;
}

const plainObject = (value) => value !== null && typeof value === "object" && !Array.isArray(value);

export function compare(before, after, additions = { files: {}, symlinks: {} }) {
  if (before?.schemaVersion !== 2 || after?.schemaVersion !== 2) {
    throw new TypeError("Record a new version-2 baseline; do not silently upgrade historical evidence");
  }
  if (!plainObject(additions) || !isDeepStrictEqual(Object.keys(additions).sort(), ["files", "symlinks"])) {
    throw new TypeError("Approved additions must contain only files and symlinks");
  }
  for (const field of ["hostname", "containers", "units", "smoke"]) {
    if (!isDeepStrictEqual(before[field], after[field])) throw new Error(`Protected host baseline changed: ${field}`);
  }
  for (const field of ["files", "symlinks"]) {
    const approved = additions[field];
    if (!plainObject(approved)) throw new TypeError("Approved additions must map absolute paths to expected values");
    for (const [entry, value] of Object.entries(approved)) {
      if (!path.isAbsolute(entry) || entry.split("/").includes("..") || typeof value !== "string" || !value) {
        throw new TypeError("Invalid approved addition");
      }
      if (Object.hasOwn(before.files, entry) || Object.hasOwn(before.symlinks, entry)) {
        throw new TypeError(`An addition cannot authorize changing an existing path: ${entry}`);
      }
    }
    if (!isDeepStrictEqual(after[field], { ...before[field], ...approved })) {
      throw new Error(`Protected configuration or approved additions differ: ${field}`);
    }
  }
}

async function main() {
  const { values } = parseArgs({
    options: {
      record: { type: "string" }, compare: { type: "string" },
      container: { type: "string", multiple: true }, unit: { type: "string", multiple: true },
      "config-dir": { type: "string", multiple: true }, "smoke-url": { type: "string" },
      additions: { type: "string" }
    }
  });
  const usage = (message) => {
    console.error(`check-shared-host: ${message}\nUsage: --record|--compare FILE --container NAME --unit NAME.service ` +
      "--config-dir DIR --smoke-url URL [--additions FILE]");
    process.exit(2);
  };
  if (Boolean(values.record) === Boolean(values.compare)) usage("exactly one of --record or --compare is required");
  for (const name of ["container", "unit", "config-dir", "smoke-url"]) if (!values[name]) usage(`--${name} is required`);
  if (values.record && values.additions) usage("--additions is only valid with --compare");
  process.umask(0o077);
  const current = await capture(values.container, values.unit, values["config-dir"], values["smoke-url"]);
  if (values.record) {
    writeFileSync(values.record, JSON.stringify(current, null, 2) + "\n", { flag: "wx" });
    console.log("Protected workload baseline recorded; no credentials or response bodies captured.");
  } else {
    const additions = values.additions ? JSON.parse(readFileSync(values.additions, "utf8")) : undefined;
    compare(JSON.parse(readFileSync(values.compare, "utf8")), current, additions);
    console.log("Protected workloads and configuration are unchanged; only exact approved additions exist.");
  }
}

if (process.argv[1] && realpathSync(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error.message);
    process.exit(1);
  });
}
