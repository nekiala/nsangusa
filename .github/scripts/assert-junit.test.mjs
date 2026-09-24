import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const script = fileURLToPath(new URL("./assert-junit.mjs", import.meta.url));
const required = [
  "com.nsangusa.news.EditorialBrokerIntegrationTests",
  "com.nsangusa.news.EventReliabilityBrokerIntegrationTests",
  "com.nsangusa.news.contracts.HttpPayloadContractTests",
  "com.nsangusa.news.contracts.EventPayloadContractTests",
  "com.nsangusa.news.contracts.StructuredContentContractTests",
  "com.nsangusa.news.contracts.SupportedV1BaselineTests",
  "com.nsangusa.news.contracts.ProtocolMigrationOverlapTests",
  "com.nsangusa.news.DeliveryWorkflowTests"
];
const live = [
  "com.nsangusa.news.media.internal.MediaDeliveryMigrationTests",
  "com.nsangusa.news.media.internal.S3MediaPersistenceTests",
  "com.nsangusa.news.newsletter.internal.MailpitNewsletterDeliveryTests"
];

function reports(t, suites) {
  const directory = mkdtempSync(path.join(tmpdir(), "nsangusa-junit-"));
  t.after(() => rmSync(directory, { recursive: true }));
  for (const { name, tests = 1, skipped = 0, failures = 0, errors = 0 } of suites) {
    writeFileSync(path.join(directory, `TEST-${name}.xml`),
      `<?xml version="1.0"?><testsuite name="${name}" tests="${tests}" skipped="${skipped}" failures="${failures}" errors="${errors}"></testsuite>`);
  }
  return directory;
}

function run(directory, mode) {
  return execFileSync(process.execPath, [script, directory, mode], { encoding: "utf8", stdio: "pipe" });
}

test("ordinary mode requires broker, contract and delivery suites and permits only explicit live opt-ins to skip", (t) => {
  const directory = reports(t, [...required.map(name => ({ name })), ...live.map(name => ({ name, skipped: 1 }))]);
  assert.match(run(directory, "ordinary"), /8 executed cases/);
});

test("live mode requires every opt-in suite to actually execute", (t) => {
  assert.match(run(reports(t, live.map(name => ({ name }))), "live"), /3 executed cases/);
  assert.throws(() => run(reports(t, live.map(name => ({ name, skipped: 1 }))), "live"), /Required tests were skipped/);
});

test("empty, missing, skipped, failed and malformed reports cannot satisfy the gate", (t) => {
  assert.throws(() => run(reports(t, []), "ordinary"), /No JUnit reports/);
  for (const missing of required) {
    const directory = reports(t, required.filter(name => name !== missing).map(name => ({ name })));
    assert.throws(() => run(directory, "ordinary"), /Missing, empty or skipped/);
  }
  for (const change of [{ tests: 0 }, { skipped: 1 }, { failures: 1 }, { errors: 1 }, { tests: "NaN" }]) {
    const directory = reports(t, required.map(name => ({ name, ...change })));
    assert.throws(() => run(directory, "ordinary"));
  }
  const unexpectedSkip = reports(t, [...required.map(name => ({ name })), { name: "ExampleTests", skipped: 1 }]);
  assert.throws(() => run(unexpectedSkip, "ordinary"), /Required tests were skipped/);
});

test("browser acceptance rejects empty, skipped, flaky and failed outcomes", (t) => {
  const directory = reports(t, []);
  const file = path.join(directory, "playwright.json");
  const browserScript = fileURLToPath(new URL("./assert-playwright.mjs", import.meta.url));
  const runBrowser = () => execFileSync(process.execPath, [browserScript, file], { encoding: "utf8", stdio: "pipe" });
  const passing = { expected: 2, unexpected: 0, skipped: 0, flaky: 0 };
  writeFileSync(file, JSON.stringify({ stats: passing, errors: [] }));
  assert.match(runBrowser(), /2 browser cases passed/);
  for (const change of [{ expected: 0 }, { skipped: 1 }, { flaky: 1 }, { unexpected: 1 }]) {
    writeFileSync(file, JSON.stringify({ stats: { ...passing, ...change }, errors: [] }));
    assert.throws(runBrowser);
  }
  writeFileSync(file, JSON.stringify({ stats: passing, errors: [{ message: "runner failed" }] }));
  assert.throws(runBrowser, /Browser runner reported errors/);
});
