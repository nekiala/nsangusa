import { randomBytes } from "node:crypto";
import { existsSync, lstatSync, mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

export function preparePreviewAi(root, allowLive = false) {
  const local = path.join(root, ".local");
  const directory = path.join(local, "runtime-secrets");
  for (const target of [local, directory]) {
    if (!existsSync(target)) mkdirSync(target, { mode: 0o700 });
    const info = lstatSync(target);
    if (!info.isDirectory() || info.isSymbolicLink()) throw new Error("Preview secret directories must be real directories");
    if (target === directory && (info.mode & 0o077) !== 0) throw new Error("Preview runtime-secrets directory must have mode 0700");
  }
  const file = path.join(directory, "nsangusa-preview-ai.env");
  const created = !existsSync(file);
  let key;
  let enabled = allowLive;
  let previouslyEnabled = false;
  if (created) key = randomBytes(32).toString("base64");
  else {
    const info = lstatSync(file);
    if (!info.isFile() || info.isSymbolicLink() || (info.mode & 0o077) !== 0) {
      throw new Error("Preview AI environment file must be a private regular file with mode 0600");
    }
    const content = readFileSync(file, "utf8");
    const match = /^AI_LIVE_ENABLED=(true|false)\nAI_CREDENTIAL_MASTER_KEY=([A-Za-z0-9+/]{43}=)\n$/.exec(content);
    if (!match || Buffer.from(match[2], "base64").length !== 32) throw new Error("Existing preview AI settings are invalid; the master key was not replaced");
    key = match[2];
    previouslyEnabled = match[1] === "true";
    enabled ||= previouslyEnabled;
  }
  if (created) {
    writeFileSync(file, `AI_LIVE_ENABLED=${enabled}\nAI_CREDENTIAL_MASTER_KEY=${key}\n`,
      { flag: "wx", mode: 0o600, flush: true });
  } else if (allowLive && !previouslyEnabled) {
    const temporary = path.join(directory, `.preview-ai-${randomBytes(8).toString("hex")}.tmp`);
    try {
      writeFileSync(temporary, `AI_LIVE_ENABLED=true\nAI_CREDENTIAL_MASTER_KEY=${key}\n`,
        { flag: "wx", mode: 0o600, flush: true });
      renameSync(temporary, file);
    } finally { if (existsSync(temporary)) unlinkSync(temporary); }
  }
  return { file, created, liveAllowed: enabled };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const args = process.argv.slice(2);
  if (args.some((arg) => arg !== "--allow-live")) throw new Error("Usage: node infrastructure/scripts/prepare-preview-ai.mjs [--allow-live]");
  const result = preparePreviewAi(path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../.."), args.includes("--allow-live"));
  console.log(`${result.created ? "Created" : "Preserved"} private preview settings: ${result.file}`);
  console.log(`Live-provider permission: ${result.liveAllowed ? "enabled" : "disabled"}. No credential was displayed and no provider was activated or called.`);
}
