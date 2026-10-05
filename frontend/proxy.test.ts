// @vitest-environment node
import { describe, expect, it } from "vitest";
import { NextRequest } from "next/server";
import nextConfig from "./next.config";
import { proxy } from "./proxy";

describe("the HTML nonce proxy", () => {
  it("overrides supplied nonce/policy with a fresh nonce shared by the request and response", () => {
    const request = new NextRequest("http://127.0.0.1:3000/sign-in", { headers: {
      "x-nonce": "attackerNonce", "Content-Security-Policy": "script-src 'unsafe-inline'"
    } });
    const response = proxy(request);
    const policy = response.headers.get("Content-Security-Policy")!;
    const nonce = policy.match(/'nonce-([^']+)'/)?.[1];
    expect(nonce).toBeTruthy();
    expect(Buffer.from(nonce!, "base64").length).toBe(18);
    expect(nonce).not.toBe("attackerNonce");
    expect(response.headers.get("x-middleware-request-x-nonce")).toBe(nonce);
    expect(response.headers.get("x-middleware-request-content-security-policy")).toBe(policy);
    expect(policy).not.toMatch(/script-src[^;]*unsafe-inline/);
    expect(response.headers.get("Cache-Control")).toBe("private, no-store, max-age=0");
    expect(proxy(request).headers.get("Content-Security-Policy")).not.toBe(policy);
  });

  it("records the locale of the requested address without rewriting or redirecting it", () => {
    const english = proxy(new NextRequest("http://127.0.0.1:3000/en/latest?page=2"));
    expect(english.headers.get("x-middleware-request-x-locale")).toBe("en");
    expect(english.headers.get("x-middleware-rewrite")).toBeNull();
    expect(english.headers.get("Content-Security-Policy")).toBeTruthy();

    const french = proxy(new NextRequest("http://127.0.0.1:3000/latest", { headers: { "x-locale": "en" } }));
    expect(french.headers.get("x-middleware-request-x-locale")).toBe("fr");
    expect(french.status).toBe(200);
  });

  it("maps /en onto the shared routes and gives French a single address in the framework configuration", async () => {
    const rewrites = await nextConfig.rewrites!();
    expect(rewrites).toMatchObject({ beforeFiles: [{ source: "/en", destination: "/" }, { source: "/en/:path*", destination: "/:path*" }] });
    expect(await nextConfig.redirects!()).toEqual([
      { source: "/fr", destination: "/", permanent: true }, { source: "/fr/:path*", destination: "/:path*", permanent: true }
    ]);
  });
});
