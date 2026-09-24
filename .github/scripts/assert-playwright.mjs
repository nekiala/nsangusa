import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const [file] = process.argv.slice(2);
assert(file, "Usage: assert-playwright.mjs REPORT_FILE");
const report = JSON.parse(await readFile(file, "utf8"));
assert(report.stats && Number.isInteger(report.stats.expected) && report.stats.expected > 0, "No browser cases passed");
for (const kind of ["unexpected", "flaky", "skipped"]) {
  assert.equal(report.stats[kind], 0, `Browser cases were ${kind}`);
}
assert.deepEqual(report.errors, [], "Browser runner reported errors");
console.log(`${report.stats.expected} browser cases passed without skips or retries`);
