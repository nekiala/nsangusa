// Driver guards and bounded Node HTTP unit fixtures; no Docker, application starts, or builds.
import assert from "node:assert/strict";
import http from "node:http";
import { after, before, describe, test } from "node:test";
import {
  assertDistinctImages, assertNoLiveState, checkedPort, checkedProject, composeDocument, decodeInternalResponse,
  DEPENDENCIES, HTTP_MAX_RESPONSE_BYTES, HttpTransportUnavailable, httpTarget, immutableReference,
  INTERNAL_HTTP_ORIGINS, NODE_HTTP_PROBE, owned, OWNER, parseOptions, Qualification, snapshotProjection, UsageError
} from "./binary-qualification.mjs";

const APPLICATIONS = ["previous-backend", "previous-frontend", "candidate-backend", "candidate-frontend"];
const completed = (status, stdout) => ({ status, stdout: Buffer.from(stdout), stderr: Buffer.alloc(0) });

describe("binary qualification guards", () => {
  test("requires full immutable references", () => {
    const digest = "a".repeat(64);
    assert.equal(immutableReference(`sha256:${digest}`), `sha256:${digest}`);
    assert.equal(immutableReference(`example.invalid/backend@sha256:${digest}`), `example.invalid/backend@sha256:${digest}`);
    for (const value of ["backend:latest", "backend:previous", "sha256:1234", "--privileged", `backend@sha256:${"a".repeat(63)}`]) {
      assert.throws(() => immutableReference(value), UsageError, value);
    }
  });

  test("cannot claim a transition with a reused image", () => {
    const images = Object.fromEntries(APPLICATIONS.map((name) => [name, { id: `sha256:${"a".repeat(64)}` }]));
    assert.throws(() => assertDistinctImages(images), /four distinct/);
  });

  test("rejects existing stack names and ports", () => {
    for (const project of ["nsangusa-preview", "nsangusa-phase4", "nsangusa-binary-other", "../../other"]) {
      assert.throws(() => checkedProject(project), /binary-qualification namespace/, project);
    }
    for (const port of ["3000", "8080", "18025", "5432", "0x1234", "-1", "65536", "018026"]) {
      assert.throws(() => checkedPort(port), UsageError, port);
    }
    assert.equal(checkedPort("0"), 0);
    assert.equal(checkedPort("18026"), 18026);
  });

  test("ownership requires the exact inventory name and both labels", () => {
    const project = "nsangusa-binary-0123456789ab";
    const name = `${project}-postgres`;
    const metadata = { Name: `/${name}`, Config: { Labels: { [OWNER]: project, "com.docker.compose.project": project } } };
    owned(metadata, "container", name, project);
    for (const key of [OWNER, "com.docker.compose.project"]) {
      const modified = structuredClone(metadata);
      modified.Config.Labels[key] = "nsangusa-preview";
      assert.throws(() => owned(modified, "container", name, project), /non-owned/, key);
    }
    assert.throws(() => owned(metadata, "container", `${project}-foreign`, project), /ownership inventory/);
  });

  test("active credentials or work cannot qualify live rollback", () => {
    const empty = { liveSetups: 0, credentials: 0, liveSettings: 0, pendingAiRequests: 0 };
    assertNoLiveState(empty);
    for (const field of Object.keys(empty)) assert.throws(() => assertNoLiveState({ ...empty, [field]: 1 }), /NOT qualified/, field);
    for (const state of [{}, null, { ...empty, credentials: false }, { ...empty, credentials: "0" }]) {
      assert.throws(() => assertNoLiveState(state), /NOT qualified/);
    }
  });

  test("snapshot preserves business fields and records monotonic source observations", () => {
    const source = { id: "source-1", version: 0, last_checked_at: null, permitted_text: "retained" };
    const original = { source_posts: [source], articles: [{ body: "retained article", version: 7 }] };
    const [baseline, observations] = snapshotProjection(original);
    const refreshed = { ...original, source_posts: [{ ...source, version: 1, last_checked_at: "2026-09-21T00:01:00+00:00" }] };
    const [current, observed] = snapshotProjection(refreshed, observations);
    assert.deepEqual(current, baseline);
    assert.equal(source.version, 0);
    assert.equal(observed["source-1"].version, 1);
    const changed = { ...refreshed, source_posts: [{ ...refreshed.source_posts[0], permitted_text: "unexpected edit" }] };
    assert.notDeepEqual(snapshotProjection(changed, observed)[0], baseline);
    // Microsecond PostgreSQL precision is retained rather than rounded to equality.
    const precise = { ...original, source_posts: [{ ...source, version: 2, last_checked_at: "2026-09-21T00:01:00.000001+00:00" }] };
    snapshotProjection(precise, observed);
    for (const invalid of [
      { ...source, version: 0, last_checked_at: "2026-09-21T00:02:00+00:00" },
      { ...source, version: 2, last_checked_at: "2026-09-21T00:00:00+00:00" },
      { ...source, version: 2, last_checked_at: null },
      { ...source, version: 1, last_checked_at: "2026-09-21T00:01:00" },
      { ...source, version: 2, last_checked_at: "2026-09-21T00:01:00+00:00" }
    ]) {
      assert.throws(() => snapshotProjection({ ...original, source_posts: [invalid] }, observed), Error, JSON.stringify(invalid));
    }
  });

  test("generated compose is isolated, bounded, fake and build-free", () => {
    const names = [...Object.keys(DEPENDENCIES), ...APPLICATIONS];
    const images = Object.fromEntries(names.map((name, index) => [name, { id: `sha256:${index.toString(16).padStart(64, "0")}` }]));
    const ports = { frontend: 13000, backend: 18080, management: 18081, mailpit: 18026 };
    const document = composeDocument("nsangusa-binary-0123456789ab", images, ports, "candidate", true);
    assert.equal(document.networks.isolated.internal, true);
    for (const [name, service] of Object.entries(document.services)) {
      assert.ok(!("build" in service), name);
      assert.equal(service.pull_policy, "never", name);
      assert.deepEqual(service.networks, ["isolated"], name);
      assert.ok("mem_limit" in service && "cpus" in service, name);
      for (const port of service.ports || []) assert.equal(port.host_ip, "127.0.0.1", name);
    }
    const environment = document.services.backend.environment;
    assert.equal(environment.AI_LIVE_ENABLED, "false");
    assert.equal(environment.PROVIDER_MODE, "fake");
    assert.equal(environment.LOCAL_SEED, "false");
    assert.equal(environment.MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED, "false");
    assert.ok(environment.AI_CREDENTIAL_MASTER_KEY.includes("${BINARY_MASTER_KEY:"));
    assert.ok(!("ports" in document.services.postgres));
    for (const topic of ["news.editorial.v1", "news.editorial.v1.retry", "news.editorial.v1.dlt"]) {
      assert.ok(document.services["kafka-init"].command[0].includes(` ${topic} `), topic);
    }
    const previous = composeDocument("nsangusa-binary-0123456789ab", images, ports, "previous", false);
    assert.equal(previous.services.backend.environment.SPRING_FLYWAY_ENABLED, "false");
    assert.equal(previous.services.backend.image, images["previous-backend"].id);
    assert.equal(document.services.kafka.volumes.length, 3);
    assert.equal(document.services["kafka-init"].volumes.length, 3);
    const internal = composeDocument("nsangusa-binary-0123456789ab", images, ports, "candidate", true, "internal");
    assert.equal(internal.networks.isolated.internal, true);
    assert.ok(Object.values(internal.services).every((service) => !("ports" in service)));
    assert.equal(internal.services.backend.environment.PUBLIC_BASE_URL, "http://127.0.0.1:13000");
  });

  test("preflight refusal never cleans pre-existing resources", () => {
    const qualification = Object.create(Qualification.prototype);
    Object.assign(qualification, {
      created: false, evidence: {}, save: () => {},
      resources: () => assert.fail("preflight refusal must not enumerate/delete resources")
    });
    qualification.cleanup();
    assert.equal(qualification.evidence.cleanup.notStarted, true);
  });

  test("all four application inputs are mandatory", () => {
    assert.throws(() => parseOptions(["--output", ".local/example"]), UsageError);
  });

  test("internal transport requires an explicit option and never retargets services", () => {
    const args = ["--output", ".local/example", ...APPLICATIONS.flatMap((name) => [`--${name}`, `sha256:${"a".repeat(64)}`])];
    assert.equal(parseOptions(args).httpTransport, "host");
    assert.equal(parseOptions([...args, "--http-transport", "internal"]).httpTransport, "internal");
    assert.throws(() => parseOptions([...args, "--http-transport", "auto"]), UsageError);
    const ports = { frontend: 13000, backend: 18080, management: 18081, mailpit: 18026 };
    for (const [service, origin] of Object.entries(INTERNAL_HTTP_ORIGINS)) {
      assert.equal(httpTarget("internal", service, "/probe", ports), `${origin}/probe`);
    }
    assert.equal(httpTarget("host", "backend", "/probe", ports), "http://127.0.0.1:18080/probe");
    for (const [service, requestPath] of [["foreign", "/probe"], ["backend", "//foreign/"], ["backend", "http://foreign/"],
      ["backend", "/bad\r\nheader"], ["backend", "/bad\\path"], ["backend", "/" + "a".repeat(2048)]]) {
      assert.throws(() => httpTarget("internal", service, requestPath, ports), Error, `${service} ${requestPath}`);
    }
  });

  function internalQualification() {
    const qualification = Object.create(Qualification.prototype);
    const project = "nsangusa-binary-0123456789ab";
    const images = { "candidate-frontend": { id: `sha256:${"a".repeat(64)}` }, "previous-frontend": { id: `sha256:${"b".repeat(64)}` } };
    const name = `${project}-frontend`;
    const metadata = {
      Name: `/${name}`, Config: { Labels: { [OWNER]: project, "com.docker.compose.project": project } },
      State: { Running: true }, Image: images["candidate-frontend"].id,
      NetworkSettings: { Networks: { [`${project}-internal`]: {} } }
    };
    const calls = { docker: [], host: 0 };
    Object.assign(qualification, {
      options: { httpTransport: "internal" }, project, ports: { backend: 18080 }, images, metadata, calls,
      dockerResult: completed(0, JSON.stringify({ status: 401, headers: { "www-authenticate": "Basic" },
        bodyBase64: Buffer.from("denied").toString("base64"), bytes: 6 })),
      inspect: () => metadata,
      docker(args, options) {
        calls.docker.push([args, options]);
        return this.dockerResult;
      },
      hostRequest: () => { calls.host += 1; }
    });
    return qualification;
  }

  test("prometheus requests allow exposition and API authentication errors", async () => {
    const qualification = internalQualification();
    for (const service of ["backend", "management"]) {
      await qualification.request(service, "/actuator/prometheus");
      const payload = JSON.parse(qualification.calls.docker.at(-1)[1].data);
      assert.equal(payload.headers.Accept, "*/*", service);
      assert.equal(payload.headers["Accept-Encoding"], "identity", service);
    }
  });

  test("internal HTTP exec is owned, bounded and preserves response fields", async () => {
    const qualification = internalQualification();
    const [status, headers, body] = await qualification.request("backend", "/api/v1/admin/users");
    assert.deepEqual([status, headers, body.toString()], [401, { "www-authenticate": "Basic" }, "denied"]);
    const [args, options] = qualification.calls.docker.at(-1);
    assert.deepEqual(args.slice(0, 5), ["exec", "-i", `${qualification.project}-frontend`, "node", "-e"]);
    assert.equal(args[5], NODE_HTTP_PROBE);
    const payload = JSON.parse(options.data);
    assert.equal(payload.url, "http://backend:8080/api/v1/admin/users");
    assert.equal(payload.maxBytes, 2 * 1024 * 1024);
    assert.equal(payload.timeoutMs, 15000);
    assert.equal(options.timeout, 20);
    assert.equal(options.check, false);
    qualification.dockerResult = completed(2, '{"error":"unavailable"}');
    await assert.rejects(qualification.request("backend", "/api/v1/admin/users"), HttpTransportUnavailable);
    assert.equal(qualification.calls.host, 0);
  });

  test("internal HTTP refuses a foreign or replaced probe container", async () => {
    for (const alteration of ["owner", "network", "image", "running"]) {
      const qualification = internalQualification();
      const metadata = qualification.metadata;
      if (alteration === "owner") metadata.Config.Labels[OWNER] = "nsangusa-preview";
      else if (alteration === "network") metadata.NetworkSettings.Networks.foreign = {};
      else if (alteration === "image") metadata.Image = `sha256:${"c".repeat(64)}`;
      else metadata.State.Running = false;
      await assert.rejects(qualification.request("backend", "/probe"), Error, alteration);
      assert.equal(qualification.calls.docker.length, 0, alteration);
    }
  });

  test("internal HTTP result cannot bypass size or format guards", () => {
    const normal = { status: 200, headers: {}, bodyBase64: "eA==", bytes: 1 };
    for (const result of [{ ...normal, bytes: 2097153 }, { ...normal, bytes: 0 }, { ...normal, status: true },
      { ...normal, bodyBase64: "!not-base64!" }, { ...normal, headers: { bad: null } }, { ...normal, error: "timeout" }, {}]) {
      assert.throws(() => decodeInternalResponse(completed(0, JSON.stringify(result))), Error, JSON.stringify(result));
    }
    assert.throws(() => decodeInternalResponse(completed(2, '{"error":"timeout"}')), HttpTransportUnavailable);
  });
});

describe("node HTTP probe", () => {
  const hits = {};
  let server;

  before(async () => {
    server = http.createServer((request, response) => {
      hits[request.url] = (hits[request.url] || 0) + 1;
      if (request.url === "/redirect") return response.writeHead(302, { Location: "/redirect-target" }).end();
      if (request.url === "/deny") return response.writeHead(403, { "X-Probe": "actual-unit-http" }).end("denied");
      if (request.url === "/declared-large") {
        response.writeHead(200, { "X-Probe": "actual-unit-http", "Content-Length": String(HTTP_MAX_RESPONSE_BYTES + 1) });
        return response.flushHeaders();
      }
      response.writeHead(200, { "X-Probe": "actual-unit-http" });
      response.on("error", () => {});
      if (request.url === "/stream-large") {
        for (let chunk = 0; chunk < 33; chunk += 1) response.write(Buffer.alloc(65536, "x"));
        return response.end();
      }
      if (request.url === "/slow") {
        let written = 0;
        const timer = setInterval(() => {
          response.write("x");
          written += 1;
          if (written === 30 || response.destroyed) {
            clearInterval(timer);
            response.end();
          }
        }, 50);
        return undefined;
      }
      return response.end(Buffer.from([0x00, ...Buffer.from("binary"), 0xff]));
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  });

  after(() => {
    server.closeAllConnections();
    server.close();
  });

  const probe = (requestPath, overrides = {}) => new Promise((resolve) => {
    // Run asynchronously so the fixture server in this process can answer.
    const payload = { url: `http://127.0.0.1:${server.address().port}${requestPath}`,
      headers: { "Accept-Encoding": "identity" }, maxBytes: HTTP_MAX_RESPONSE_BYTES, timeoutMs: 15000, ...overrides };
    import("node:child_process").then(({ spawn }) => {
      const child = spawn(process.execPath, ["-e", NODE_HTTP_PROBE], { env: { ...process.env, NODE_OPTIONS: "" } });
      const chunks = [];
      child.stdout.on("data", (chunk) => chunks.push(chunk));
      child.on("close", (status) => resolve({ status, stdout: Buffer.concat(chunks) }));
      child.stdin.end(JSON.stringify(payload));
    });
  });

  test("reads actual status, headers and binary body", async () => {
    let [status, headers, body] = decodeInternalResponse(await probe("/ok"));
    assert.equal(status, 200);
    assert.equal(headers["x-probe"], "actual-unit-http");
    assert.deepEqual([...body], [0x00, ...Buffer.from("binary"), 0xff]);
    [status, headers, body] = decodeInternalResponse(await probe("/deny"));
    assert.deepEqual([status, body.toString()], [403, "denied"]);
  });

  test("a redirect is returned without following it", async () => {
    const before = hits["/redirect-target"] || 0;
    const [status, headers, body] = decodeInternalResponse(await probe("/redirect"));
    assert.deepEqual([status, headers.location, body.length], [302, "/redirect-target", 0]);
    assert.equal(hits["/redirect-target"] || 0, before);
  });

  test("refuses declared and streamed oversize responses", async () => {
    for (const requestPath of ["/declared-large", "/stream-large"]) {
      const response = await probe(requestPath);
      assert.equal(response.status, 2, requestPath);
      assert.equal(JSON.parse(response.stdout).error, "response_too_large", requestPath);
    }
  });

  test("the deadline covers a continuously streaming response", async () => {
    const response = await probe("/slow", { timeoutMs: 150 });
    assert.equal(response.status, 2);
    assert.equal(JSON.parse(response.stdout).error, "timeout");
  });

  test("the caller cannot increase hard limits", async () => {
    for (const limits of [{ maxBytes: 2097153 }, { timeoutMs: 15001 }, { timeoutMs: 0 }]) {
      const response = await probe("/ok", limits);
      assert.equal(response.status, 2, JSON.stringify(limits));
      assert.equal(JSON.parse(response.stdout).error, "invalid_request");
    }
  });
});
