import js from "@eslint/js";
import nextVitals from "eslint-config-next/core-web-vitals";

const config = [
  { ignores: [".next/**", ".next-*/**", "node_modules/**", "playwright-report/**", "test-results/**"] },
  js.configs.recommended,
  ...nextVitals,
  {
    rules: { "no-unused-vars": ["error", { argsIgnorePattern: "^_" }] },
  },
];
export default config;
