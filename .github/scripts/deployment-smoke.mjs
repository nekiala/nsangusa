import { pathToFileURL } from "node:url";

function requireValue(condition, message) {
  if (!condition) throw new Error(message);
}
export function smokeTarget(origin, allowLoopbackHttp = false) {
  const url = new URL(origin);
  const localHttp = allowLoopbackHttp && url.protocol === "http:" && ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
  requireValue((url.protocol === "https:" || localHttp) && !url.username && !url.password
    && url.pathname === "/" && !url.search && !url.hash, "Smoke target must be an exact HTTPS origin (explicit loopback HTTP is only for local exercises)");
  return url.origin;
}
// An access boundary in front of a test environment (for example proxy basic auth) must not
// change what the smoke checks observe behind it; credentials never travel in the origin URL.
export function smokeHeaders(basicAuth = "") {
  if (!basicAuth) return {};
  requireValue(/^[^:\s]+:\S+$/.test(basicAuth), "Smoke basic auth must be user:password");
  return { Authorization: `Basic ${Buffer.from(basicAuth).toString("base64")}` };
}
export async function deploymentSmoke(origin, { allowLoopbackHttp = false, fetcher = fetch, basicAuth = "" } = {}) {
  const target = smokeTarget(origin, allowLoopbackHttp);
  const headers = smokeHeaders(basicAuth);
  const results = [];
  for (const [path, expected] of [
    ["/sign-in", 200], ["/", 200], ["/api/v1/articles?limit=1", 200],
    ["/api/v1/auth/me", 401], ["/api/v1/admin/users", 401], ["/robots.txt", 200], ["/sitemap.xml", 200]
  ]) {
    const response = await fetcher(`${target}${path}`, {
      redirect: "error", cache: "no-store", headers, signal: AbortSignal.timeout(15_000)
    });
    try {
      requireValue(response.status === expected, `Deployment smoke failed: ${path} must return ${expected} (received ${response.status})`);
      requireValue(/\bno-store\b/i.test(response.headers.get("cache-control") || ""), `Deployment smoke failed: ${path} is missing no-store`);
      if (path === "/" || path === "/sign-in") {
        const policy = response.headers.get("content-security-policy") || "";
        const scripts = /(?:^|;)\s*script-src\s+([^;]+)/.exec(policy)?.[1] || "";
        requireValue(/'nonce-[A-Za-z0-9+/=_-]+'/.test(scripts) && !/'unsafe-(?:inline|eval)'/.test(scripts),
          `Deployment smoke failed: ${path} must enforce nonce-based script CSP`);
      }
      results.push({ path, status: response.status, result: "passed" });
    } finally { await response.body?.cancel(); }
  }
  return { origin: target, completedAt: new Date().toISOString(), checks: results };
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (process.argv.includes("--validate-target")) {
    smokeTarget(process.env.PUBLIC_SMOKE_URL);
    console.log("HTTPS smoke target is configured.");
  } else {
    console.log(JSON.stringify(await deploymentSmoke(process.env.PUBLIC_SMOKE_URL, {
      allowLoopbackHttp: process.argv.includes("--allow-loopback-http"),
      basicAuth: process.env.SMOKE_BASIC_AUTH || ""
    }), null, 2));
  }
}
