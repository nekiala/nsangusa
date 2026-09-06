import js from "@eslint/js";
import nextVitals from "eslint-config-next/core-web-vitals";

const config = [
  js.configs.recommended,
  ...nextVitals,
  {
    ignores: [".next/**", "node_modules/**", "playwright-report/**"],
    rules: { "no-unused-vars": ["error", { argsIgnorePattern: "^_" }] },
  },
];
export default config;
