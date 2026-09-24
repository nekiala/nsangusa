import { describe, expect, it } from "vitest";
import { contentSecurityPolicy, trustedApiOrigin } from "./content-security-policy";

describe("the frontend Content Security Policy", () => {
  it("uses a nonce without script unsafe-inline, unsafe-eval, or wildcard sources in production", () => {
    const policy = contentSecurityPolicy("uniqueNonce123");
    expect(policy).toContain("script-src 'self' 'nonce-uniqueNonce123' 'strict-dynamic'");
    expect(policy).toContain("script-src-attr 'none'");
    expect(policy).toContain("connect-src 'self';");
    expect(policy).toContain("img-src 'self' blob: data:;");
    expect(policy).toContain("style-src 'self' 'nonce-uniqueNonce123'");
    expect(policy).toContain("object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'; frame-src 'none'");
    expect(policy).not.toMatch(/script-src[^;]*(unsafe-inline|unsafe-eval)/);
    expect(policy).not.toContain("*");
    expect(policy).not.toContain("upgrade-insecure-requests");
  });

  it("trusts only the configured browser API origin, not the internal server destination", () => {
    const policy = contentSecurityPolicy("nonce", { apiUrl: "https://api.example.test:8443/" });
    expect(policy).toContain("connect-src 'self' https://api.example.test:8443;");
    expect(policy).toContain("img-src 'self' blob: data: https://api.example.test:8443;");
    expect(policy.match(/script-src [^;]+/)?.[0]).not.toContain("api.example.test");
    expect(contentSecurityPolicy("nonce", { apiUrl: "http://127.0.0.1:18080" })).toContain("connect-src 'self' http://127.0.0.1:18080;");
  });

  it.each(["https://*.example.test", "https://%2a.example.test", "https://%3b.example.test", "javascript:alert(1)", "data:text/plain,test", "/api",
    "https://user:password@api.example.test", "https://api.example.test/path",
    "https://api.example.test/?query=1", "https://api.example.test/#fragment"])("rejects ambiguous or unsafe API configuration: %s", (url) => {
    expect(() => trustedApiOrigin(url)).toThrow();
  });

  it("permits only the explicit local websocket origin and eval in development", () => {
    const policy = contentSecurityPolicy("nonce", { development: true, origin: "http://127.0.0.1:3000" });
    expect(policy).toContain("'unsafe-eval'");
    expect(policy).toContain("connect-src 'self' ws://127.0.0.1:3000;");
    expect(policy).not.toContain("*");
  });

  it("rejects nonce directive injection", () => {
    expect(() => contentSecurityPolicy("bad'; script-src *")).toThrow();
  });
});
