import { defineConfig, devices } from "@playwright/test";

// The port is configurable so the suite can run beside another local stack on 3000.
const port = process.env.E2E_PORT || "3000";
const origin = `http://127.0.0.1:${port}`;

export default defineConfig({
  testDir: "./e2e",
  testIgnore: "**/fullstack/**",
  fullyParallel: true,
  use: { baseURL: origin, trace: "on-first-retry" },
  webServer: {
    command: `NEXT_PUBLIC_API_MODE=fake npm run build && mkdir -p .next/standalone/.next && cp -R .next/static .next/standalone/.next/static && HOSTNAME=127.0.0.1 PORT=${port} node .next/standalone/server.js`,
    url: origin,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }]
});
