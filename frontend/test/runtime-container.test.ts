// @vitest-environment node
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, test } from "vitest";

const runtime = "gcr.io/distroless/nodejs24-debian13:nonroot@sha256:bb6b03d81066993293a10feda7250e8e1cc034035fe9b61cfceededa7c8bf04d";

describe("frontend runtime Dockerfiles", () => {
  for (const file of ["Dockerfile", "../infrastructure/docker/frontend.Dockerfile"]) {
    test(`${file} preserves the qualified runtime and deployment contract`, () => {
      const dockerfile = readFileSync(resolve(file), "utf8");
      const runner = dockerfile.slice(dockerfile.lastIndexOf("\nFROM ") + 1);
      expect(runner.split("\n")[0]).toMatch(new RegExp(`^FROM ${runtime.replaceAll(".", "\\.")}( AS runner)?$`));
      expect(runner).toContain("USER 10001:10001");
      expect(runner.lastIndexOf("USER 10001:10001")).toBeGreaterThan(runner.lastIndexOf("USER 0"));
      expect(runner).toContain("--chown=10001:10001");
      expect(runner).toContain("HOME=/app");
      expect(runner).toContain("PATH=/nodejs/bin:");
      expect(runner).toContain('ENTRYPOINT ["/nodejs/bin/node"]');
      expect(runner).toContain('CMD ["server.js"]');
      expect(runner).toContain("STOPSIGNAL SIGTERM");
      expect(runner).toContain("/etc/passwd");
      expect(runner).toContain("/etc/group");
      expect(runner).toContain("10001:10001:");
      expect(runner).not.toMatch(/apt-get|npm uninstall|\/var\/lib\/dpkg|rm -rf|--platform=/);
    });
  }
});
