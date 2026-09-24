import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  testIgnore: "**/fullstack/**",
  fullyParallel: true,
  use: { baseURL: "http://127.0.0.1:3000", trace: "on-first-retry" },
  webServer: {
    command: "NEXT_PUBLIC_API_MODE=fake npm run build && mkdir -p .next/standalone/.next && cp -R .next/static .next/standalone/.next/static && HOSTNAME=127.0.0.1 PORT=3000 node .next/standalone/server.js",
    url: "http://127.0.0.1:3000",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }]
});
