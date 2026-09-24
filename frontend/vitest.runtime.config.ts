import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "node",
    include: ["test/runtime-api-proxy.integration.ts"],
    testTimeout: 30_000,
    hookTimeout: 10_000
  }
});
