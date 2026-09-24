import { afterEach, describe, expect, it, vi } from "vitest";
import { publicBaseUrl } from "./public-base-url";

afterEach(() => vi.unstubAllEnvs());

describe("runtime publication origin", () => {
  it("prefers PUBLIC_BASE_URL and reads it when called, not at build or module initialization", () => {
    vi.stubEnv("NEXT_PUBLIC_SITE_URL", "http://build-time.invalid");
    vi.stubEnv("PUBLIC_BASE_URL", "https://publication.example.test/");
    expect(publicBaseUrl()).toBe("https://publication.example.test");
    vi.stubEnv("PUBLIC_BASE_URL", "http://127.0.0.1:13000");
    expect(publicBaseUrl()).toBe("http://127.0.0.1:13000");
  });

  it("preserves the build-time and local defaults when the runtime override is absent", () => {
    vi.stubEnv("PUBLIC_BASE_URL", "");
    vi.stubEnv("NEXT_PUBLIC_SITE_URL", "https://legacy-publication.example.test");
    expect(publicBaseUrl()).toBe("https://legacy-publication.example.test");
    vi.stubEnv("NEXT_PUBLIC_SITE_URL", "");
    expect(publicBaseUrl()).toBe("http://localhost:3000");
  });

  it.each(["javascript:alert(1)", "https://user:secret@example.test", "https://*.example.test",
    "https://example.test/path", "https://example.test/?query=1", "https://example.test/#fragment"])("rejects invalid runtime configuration without falling back: %s", (value) => {
    vi.stubEnv("PUBLIC_BASE_URL", value);
    vi.stubEnv("NEXT_PUBLIC_SITE_URL", "https://fallback.example.test");
    expect(() => publicBaseUrl()).toThrow("publication base URL");
  });
});
