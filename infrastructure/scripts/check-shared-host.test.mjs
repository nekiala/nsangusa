import assert from "node:assert/strict";
import { mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import http from "node:http";
import path from "node:path";
import { test } from "node:test";
import { capture, compare, CONTAINER_FORMAT, smoke } from "./check-shared-host.mjs";

function baseline() {
  return {
    schemaVersion: 2, hostname: "fixture",
    containers: { existing: {
      id: "original", image: "sha256:original", startedAt: "original", restartCount: 0, health: "healthy"
    } },
    units: { "nginx.service": { MainPID: "123", ActiveState: "active", ActiveEnterTimestampMonotonic: "12345" } },
    files: { "/etc/nginx/nginx.conf": "original" },
    symlinks: { "/etc/nginx/sites-enabled/site": "../sites-available/site" },
    smoke: { url: "http://127.0.0.1:3000/", status: 200 }
  };
}

test("identical baseline passes", () => {
  compare(baseline(), baseline());
});

test("replacement, restart, image change and unhealthy state fail", () => {
  for (const [field, value] of [["id", "replacement"], ["image", "sha256:new"], ["startedAt", "new"],
    ["restartCount", 1], ["health", "unhealthy"]]) {
    const changed = baseline();
    changed.containers.existing[field] = value;
    assert.throws(() => compare(baseline(), changed), { name: "Error" }, field);
  }
});

test("host or service restart fails", () => {
  for (const field of ["hostname", "units", "smoke"]) {
    const changed = baseline();
    changed[field] = null;
    assert.throws(() => compare(baseline(), changed), { name: "Error" }, field);
  }
});

test("site enablement changes and legacy baselines fail", () => {
  const changed = baseline();
  changed.symlinks = {};
  assert.throws(() => compare(baseline(), changed), { name: "Error" });
  const legacy = baseline();
  legacy.schemaVersion = 1;
  assert.throws(() => compare(legacy, baseline()), TypeError);
});

test("capture includes link targets but never reads certificate keys", async () => {
  const directory = mkdtempSync(path.join(tmpdir(), "shared-host-"));
  try {
    writeFileSync(path.join(directory, "site.conf"), "server {}");
    symlinkSync("site.conf", path.join(directory, "enabled-site"));
    writeFileSync(path.join(directory, "private.key"), "synthetic key fixture");
    symlinkSync("private.key", path.join(directory, "key-alias"));
    const outputs = [
      '{"health":"healthy"}',
      "ActiveState=active\nMainPID=123\nActiveEnterTimestampMonotonic=12345\n"
    ];
    const state = await capture(["existing"], ["nginx.service"], [directory], "unused", {
      smoke: async () => ({}), command: () => outputs.shift()
    });
    assert.equal(state.symlinks[path.join(directory, "enabled-site")], "site.conf");
    assert.equal(state.files[path.join(directory, "enabled-site")], state.files[path.join(directory, "site.conf")]);
    assert.ok(!(path.join(directory, "private.key") in state.files));
    assert.ok(!(path.join(directory, "key-alias") in state.files));
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("configuration edit, removal and addition fail", () => {
  for (const files of [{}, { "/etc/nginx/nginx.conf": "changed" },
    { ...baseline().files, "/etc/nginx/new.conf": "new" }]) {
    const changed = baseline();
    changed.files = files;
    assert.throws(() => compare(baseline(), changed), { name: "Error" });
  }
});

test("only exact approved new configuration is permitted", () => {
  const additions = {
    files: { "/etc/nginx/sites-available/staging": "sha256-expected", "/etc/nginx/sites-enabled/staging": "sha256-expected" },
    symlinks: { "/etc/nginx/sites-enabled/staging": "../sites-available/staging" }
  };
  const changed = baseline();
  for (const field of Object.keys(additions)) Object.assign(changed[field], additions[field]);
  compare(baseline(), changed, additions);
  for (const [field, entry] of [["files", "/etc/nginx/sites-available/staging"], ["symlinks", "/etc/nginx/sites-enabled/staging"]]) {
    const invalid = structuredClone(changed);
    invalid[field][entry] = "unexpected";
    assert.throws(() => compare(baseline(), invalid, additions), { name: "Error" }, field);
  }
  assert.throws(() => compare(baseline(), baseline(), additions), { name: "Error" });
});

test("approved additions cannot override or omit protected state", () => {
  for (const additions of [
    { files: { "/etc/nginx/nginx.conf": "changed" }, symlinks: {} },
    { files: {}, symlinks: { "/etc/nginx/sites-enabled/site": "changed" } },
    { files: { "/etc/nginx/sites-enabled/site": "changed" }, symlinks: {} },
    { files: {}, symlinks: {}, units: {} },
    { files: { relative: "changed" }, symlinks: {} },
    { files: { "/etc/nginx/../nginx/new.conf": "changed" }, symlinks: {} },
    { files: [], symlinks: {} }
  ]) {
    assert.throws(() => compare(baseline(), baseline(), additions), TypeError, JSON.stringify(additions));
  }
});

test("remote or credential-bearing smoke targets fail before network I/O", async () => {
  for (const url of ["https://example.test", "http://user:password@localhost/",
    "http://127.0.0.1/?token=secret", "http://127.0.0.1/#fragment", "file:///etc/passwd"]) {
    await assert.rejects(smoke(url), TypeError, url);
  }
});

test("redirects fail instead of following an external target", async () => {
  let followed = false;
  const server = http.createServer((request, response) => {
    if (request.url === "/target") followed = true;
    response.writeHead(request.url === "/" ? 302 : 200, { Location: "https://example.test/" }).end();
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    await assert.rejects(smoke(`http://127.0.0.1:${server.address().port}/`), /must not redirect/);
    assert.equal(followed, false);
    assert.deepEqual(await smoke(`http://127.0.0.1:${server.address().port}/ok`),
      { url: `http://127.0.0.1:${server.address().port}/ok`, status: 200 });
  } finally {
    server.close();
  }
});

test("container projection never requests environment or health output", () => {
  for (const field of [".Config", ".Log", ".Output"]) assert.ok(!CONTAINER_FORMAT.includes(field));
});
