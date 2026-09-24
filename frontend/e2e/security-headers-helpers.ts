import { expect, type Page, type Response } from "@playwright/test";

function nonceFrom(policy: string) {
  const nonce = policy.match(/'nonce-([A-Za-z0-9+/=]+)'/)?.[1];
  expect(nonce).toBeTruthy();
  expect(Buffer.from(nonce!, "base64").length).toBeGreaterThanOrEqual(16);
  return nonce!;
}

export function expectSecurityHeaders(response: Response, sensitive = false) {
  const headers = response.headers();
  expect(headers["x-content-type-options"]).toBe("nosniff");
  expect(headers["x-frame-options"]).toBe("DENY");
  expect(headers["referrer-policy"]).toBe(sensitive ? "no-referrer" : "strict-origin-when-cross-origin");
  expect(headers["permissions-policy"]).toBe("camera=(), microphone=(), geolocation=()");
  expect(headers["cache-control"]).toContain("no-store");
  expect(headers["x-powered-by"]).toBeUndefined();
  const policy = headers["content-security-policy"];
  expect(policy).toContain("frame-ancestors 'none'");
  expect(policy).toContain("object-src 'none'");
  expect(policy).toContain("base-uri 'none'");
  expect(policy).toContain("form-action 'self'");
  expect(policy).toContain("script-src-attr 'none'");
  expect(policy).not.toMatch(/script-src[^;]*(unsafe-inline|unsafe-eval)/);
  expect(policy).not.toContain("*");
  return nonceFrom(policy);
}

export async function securityHeaderJourney(page: Page) {
  await page.addInitScript(() => {
    const state = window as typeof window & { cspViolations: string[]; untrustedInlineRan?: boolean };
    state.cspViolations = [];
    document.addEventListener("securitypolicyviolation", (event) => state.cspViolations.push(event.effectiveDirective));
  });
  const first = await page.goto("/sign-in");
  const nonce = expectSecurityHeaders(first!, true);
  const scripts = page.locator("script:not([type='application/ld+json'])");
  expect(await scripts.count()).toBeGreaterThan(0);
  expect(await scripts.evaluateAll((elements, expected) => elements.every((element) =>
    (element as HTMLScriptElement).nonce === expected), nonce), "Next bootstrap and asset scripts carry the request nonce").toBe(true);
  await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
  const second = await page.reload();
  expect(expectSecurityHeaders(second!, true)).not.toBe(nonce);
  const spoof = await page.request.get("/newsletter", { headers: {
    "x-nonce": "attackerNonce", "Content-Security-Policy": "script-src 'unsafe-inline'"
  } });
  expect(nonceFrom(spoof.headers()["content-security-policy"])).not.toBe("attackerNonce");
  expect(spoof.headers()["content-security-policy"]).not.toMatch(/script-src[^;]*unsafe-inline/);
  await page.getByRole("navigation", { name: "Primary navigation" }).getByRole("link", { name: "Newsletter", exact: true }).click();
  await expect(page).toHaveURL(/\/newsletter$/);
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  const email = page.getByRole("main").getByLabel("Email address", { exact: true });
  await email.fill("not-an-email");
  await page.getByRole("main").getByRole("button", { name: "Subscribe", exact: true }).click();
  expect(await email.evaluate((element) => (element as HTMLInputElement).validity.typeMismatch)).toBe(true);
  expect(await page.evaluate(() => (window as typeof window & { cspViolations: string[] }).cspViolations)).toEqual([]);
  // Inject into a real HTML response, not DevTools evaluate (which is a trusted execution context).
  const probe = "**/sign-in?csp-probe=1";
  await page.route(probe, async (route) => {
    const response = await route.fetch();
    const body = (await response.text()).replace("</body>",
      '<script>window.untrustedInlineRan = true</script><button id="untrusted-handler" onclick="window.untrustedInlineRan = true">CSP probe</button></body>');
    await route.fulfill({ response, body });
  });
  await page.goto("/sign-in?csp-probe=1");
  await expect.poll(() => page.evaluate(() => (window as typeof window & { cspViolations: string[] }).cspViolations)).toContain("script-src-elem");
  expect(await page.evaluate(() => (window as typeof window & { untrustedInlineRan?: boolean }).untrustedInlineRan)).toBeUndefined();
  await page.locator("#untrusted-handler").click();
  await expect.poll(() => page.evaluate(() => (window as typeof window & { cspViolations: string[] }).cspViolations)).toContain("script-src-attr");
  expect(await page.evaluate(() => (window as typeof window & { untrustedInlineRan?: boolean }).untrustedInlineRan)).toBeUndefined();
  await page.unroute(probe);
  const publicPage = await page.goto("/");
  expectSecurityHeaders(publicPage!);
}
