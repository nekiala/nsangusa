import { defineConfig, devices } from "@playwright/test";

const frontend = process.env.FULLSTACK_FRONTEND_URL || "http://127.0.0.1:13000";
const backend = process.env.FULLSTACK_API_URL || "http://127.0.0.1:18080";
const address = new URL(frontend);

export default defineConfig({
  testDir: "./e2e/fullstack",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 240_000,
  expect: { timeout: 20_000 },
  use: { baseURL: frontend, trace: "retain-on-failure", timezoneId: "UTC" },
  webServer: process.env.FULLSTACK_START_FRONTEND === "false" ? undefined : {
    command: "npm run build && mkdir -p .next-fullstack/standalone/.next-fullstack && cp -R .next-fullstack/static .next-fullstack/standalone/.next-fullstack/static && node .next-fullstack/standalone/server.js",
    url: `${frontend}/sign-in`,
    env: { NEXT_PUBLIC_API_MODE: "api", NEXT_PUBLIC_API_URL: "", NSANGUSA_API_URL: backend, PUBLIC_BASE_URL: frontend, NEXT_PUBLIC_SITE_URL: frontend, NEXT_DIST_DIR: ".next-fullstack", HOSTNAME: address.hostname, PORT: address.port || "13000" },
    reuseExistingServer: process.env.FULLSTACK_REUSE_FRONTEND === "true",
    timeout: 120_000
  },
  projects: [{ name: "real-backend-chromium", use: { ...devices["Desktop Chrome"] } }]
});
