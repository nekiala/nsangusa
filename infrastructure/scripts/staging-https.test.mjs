import assert from "node:assert/strict";
import { execFile, execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";
import tls from "node:tls";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

const ASSETS = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../nginx/staging");
const read = (name) => readFileSync(path.join(ASSETS, name), "utf8");
const HOST = "staging.nsangusa.com";

function ini(text) {
  return Object.fromEntries(text.split("\n").map((line) => line.trim())
    .filter((line) => line && !line.startsWith("#") && !line.startsWith(";"))
    .map((line) => {
      const separator = line.search(/[=:]/);
      return separator < 0 ? [line, ""] : [line.slice(0, separator).trim(), line.slice(separator + 1).trim()];
    }));
}

test("ACME uses an isolated webroot without nginx or standalone plugins", () => {
  const options = ini(read("cli.ini"));
  assert.equal(options["config-dir"], "/etc/nsangusa-acme");
  assert.equal(options["work-dir"], "/var/lib/nsangusa-acme");
  assert.equal(options["logs-dir"], "/var/log/nsangusa-acme");
  assert.equal(options["webroot-path"], "/var/www/nsangusa-acme");
  assert.equal(options.authenticator, "webroot");
  assert.equal(options.server, "https://acme-v02.api.letsencrypt.org/directory");
  assert.equal(options.email, "contact@nsangusa.com");
  for (const flag of ["agree-tos", "non-interactive"]) assert.ok(["", "true", "yes", "1", "on"].includes(options[flag]), flag);
  assert.ok(!("installer" in options));
});

test("only the staging hostname and its certificate are configured", () => {
  for (const name of ["http-bootstrap.conf", "https.conf"]) {
    const text = read(name);
    for (const line of text.split("\n")) {
      if (line.trim().startsWith("server_name ")) assert.equal(line.trim(), `server_name ${HOST};`, name);
    }
    for (const forbidden of ["default_server", "/etc/letsencrypt"]) assert.ok(!text.includes(forbidden), `${name}: ${forbidden}`);
    assert.ok(text.includes("location ^~ /.well-known/acme-challenge/"), name);
    assert.ok(text.includes("try_files $uri =404;"), name);
  }
  const bootstrap = read("http-bootstrap.conf");
  assert.ok(!bootstrap.includes("proxy_pass"));
  assert.ok(bootstrap.includes('return 503 "Nsangusa staging is not yet deployed.\\n";'));
  const https = read("https.conf");
  for (const expected of ["ssl_protocols TLSv1.2 TLSv1.3;", `return 308 https://${HOST}$request_uri;`,
    "ssl_session_tickets off;", 'add_header Cache-Control "no-store" always;',
    'add_header X-Robots-Tag "noindex, nofollow" always;']) assert.ok(https.includes(expected), expected);
  for (const forbidden of ["includeSubDomains", "preload"]) assert.ok(!https.includes(forbidden), forbidden);
});

test("test environment is proxied only to loopback NodePorts behind basic auth", () => {
  const tlsConfig = read("https.conf");
  const httpsServer = tlsConfig.slice(tlsConfig.indexOf("listen 443 ssl;"));
  const targets = httpsServer.split("\n").filter((line) => line.trim().startsWith("proxy_pass "))
    .map((line) => line.trim().split(/\s+/)[1].replace(/;$/, ""));
  assert.deepEqual(targets.sort(), ["http://127.0.0.1:30380", "http://127.0.0.1:30381"]);
  assert.ok(!tlsConfig.slice(0, tlsConfig.indexOf("listen 443 ssl;")).includes("proxy_pass"));
  assert.ok(httpsServer.includes('auth_basic "Nsangusa test environment";'));
  assert.ok(httpsServer.includes("auth_basic_user_file /etc/nginx/nsangusa-test.htpasswd;"));
  // The only unauthenticated location is the HTTPS challenge path, which serves nothing.
  let acme = httpsServer.slice(httpsServer.indexOf("location ^~ /.well-known/acme-challenge/"));
  acme = acme.slice(0, acme.indexOf("}"));
  assert.equal(httpsServer.split("auth_basic off;").length - 1, 1);
  assert.ok(acme.includes("auth_basic off;"));
  assert.ok(acme.includes("return 404;"));
  // Credentials stop at the proxy and clients cannot inject forwarded addresses.
  assert.ok(httpsServer.includes('proxy_set_header Authorization "";'));
  assert.ok(httpsServer.includes("proxy_set_header X-Forwarded-For $remote_addr;"));
  assert.ok(!httpsServer.includes("$proxy_add_x_forwarded_for"));
  assert.ok(httpsServer.includes("location ~ ^/(api|login|logout|oauth2)(/|$)"));
  assert.ok(httpsServer.includes('return 503 "Nsangusa test environment is unavailable.\\n";'));
});

test("renewal is scoped, bounded and does not force new certificates", () => {
  const service = read("nsangusa-acme-renew.service");
  for (const expected of ["--config /etc/nsangusa-acme/cli.ini", `--cert-name ${HOST}`,
    "--deploy-hook /usr/local/libexec/nsangusa-acme-reload", "ExecStartPost=/usr/local/libexec/nsangusa-acme-verify",
    "TimeoutStartSec=15min", "UMask=0077",
    `ExecStartPre=/usr/bin/test -s /etc/nsangusa-acme/renewal/${HOST}.conf`]) assert.ok(service.includes(expected), expected);
  for (const forbidden of ["ConditionPathExists=", "--force-renewal", "--dry-run"]) assert.ok(!service.includes(forbidden), forbidden);
  const timer = read("nsangusa-acme-renew.timer");
  for (const expected of ["OnCalendar=*-*-* 00,12:00:00", "RandomizedDelaySec=1h", "Persistent=true"]) {
    assert.ok(timer.includes(expected), expected);
  }
});

test("reload requires a valid certificate and configuration without restart", () => {
  const hook = read("nsangusa-acme-reload");
  for (const expected of [`-checkhost ${HOST}`, "-checkend 604800", "/usr/sbin/nginx -t",
    "/usr/bin/systemctl reload nginx.service"]) assert.ok(hook.includes(expected), expected);
  assert.ok(hook.indexOf("-checkhost") < hook.indexOf("/usr/sbin/nginx -t"));
  assert.ok(hook.indexOf("/usr/sbin/nginx -t") < hook.indexOf("systemctl reload"));
  for (const forbidden of ["restart", "stop"]) assert.ok(!hook.includes(forbidden), forbidden);
});

test("unexpected lineages or domains fail before certificate or service access", () => {
  const valid = `/etc/nsangusa-acme/live/${HOST}`;
  for (const [lineage, domains] of [["", ""], ["/etc/letsencrypt/live/other", HOST],
    [valid, "other.example"], [valid, `${HOST} other.example`], [valid + "/../other", HOST]]) {
    const result = spawnSync("sh", [path.join(ASSETS, "nsangusa-acme-reload")], {
      env: { ...process.env, RENEWED_LINEAGE: lineage, RENEWED_DOMAINS: domains }, encoding: "utf8", timeout: 5000
    });
    assert.equal(result.status, 1, `${lineage} ${domains}`);
    assert.ok(result.stderr.includes("unexpected lineage or domain"));
    assert.equal(result.stdout, "");
  }
});

test("the verifier is a POSIX shell script, not an additional runtime", () => {
  assert.ok(read("nsangusa-acme-verify").startsWith("#!/bin/sh\n"));
  assert.equal(spawnSync("sh", ["-n", path.join(ASSETS, "nsangusa-acme-verify")]).status, 0);
});

// Real TLS fixtures exercise the verifier's handshake, comparison and expiry decisions.
let fixtures;
const servers = [];

function certificate(name, days, subject = HOST) {
  const key = path.join(fixtures, `${name}.key`);
  const cert = path.join(fixtures, `${name}.pem`);
  execFileSync("openssl", ["req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
    "-nodes", "-keyout", key, "-out", cert, "-days", String(days), "-subj", `/CN=${subject}`,
    "-addext", `subjectAltName=DNS:${subject}`], { stdio: "ignore" });
  return { key: readFileSync(key), cert: readFileSync(cert), path: cert };
}

async function serve(initial) {
  let current = initial;
  const server = tls.createServer({ SNICallback: (_name, done) => done(null, tls.createSecureContext(current)) },
    (socket) => socket.end());
  server.on("tlsClientError", () => {});
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  servers.push(server);
  return { port: server.address().port, swap: (next) => { current = next; } };
}

function verify(port, onDisk, trusted) {
  return promisify(execFile)("sh", [path.join(ASSETS, "nsangusa-acme-verify")], {
    env: { ...process.env, NSANGUSA_ACME_PORT: String(port), NSANGUSA_ACME_CERTIFICATE: onDisk.path,
      NSANGUSA_ACME_CA_FILE: trusted.path },
    timeout: 30_000
  }).then(({ stdout }) => ({ status: 0, stdout }), (error) => ({ status: error.code, stderr: error.stderr }));
}

before(() => {
  fixtures = mkdtempSync(path.join(tmpdir(), "acme-verify-"));
});

after(() => {
  for (const server of servers) server.close();
  rmSync(fixtures, { recursive: true, force: true });
});

test("served certificate must be trusted, match disk and not be near expiry", { timeout: 60_000 }, async () => {
  const current = certificate("current", 90);
  const old = certificate("old", 90);
  const expiring = certificate("expiring", 3);
  const foreign = certificate("foreign", 90, "other.example");
  const servingCurrent = await serve(current);
  const servingOld = await serve(old);
  const servingExpiring = await serve(expiring);
  const servingForeign = await serve(foreign);
  const [accepted, stale, nearExpiry, untrusted, wrongHost] = await Promise.all([
    verify(servingCurrent.port, current, current),
    verify(servingOld.port, current, trustBundle(current, old)),
    verify(servingExpiring.port, expiring, expiring),
    verify(servingCurrent.port, current, old),
    verify(servingForeign.port, foreign, foreign)
  ]);
  assert.equal(accepted.status, 0);
  assert.match(accepted.stdout, /^Nginx serves the current trusted staging certificate: SHA256 [a-f0-9]{64}\n$/);
  for (const [name, result] of Object.entries({ stale, nearExpiry, untrusted, wrongHost })) {
    assert.equal(result.status, 1, name);
    assert.match(result.stderr, /Staging certificate activation failed/, name);
  }
});

test("a graceful reload can converge but never masks a persistent failure", { timeout: 30_000 }, async () => {
  const current = certificate("converge-current", 90);
  const old = certificate("converge-old", 90);
  const server = await serve(old);
  setTimeout(() => server.swap(current), 2500);
  const result = await verify(server.port, current, trustBundle(current, old));
  assert.equal(result.status, 0);
});

let bundles = 0;
function trustBundle(...certificates) {
  bundles += 1;
  const bundle = path.join(fixtures, `bundle-${bundles}.pem`);
  writeFileSync(bundle, certificates.map((item) => item.cert).join(""));
  return { path: bundle };
}
