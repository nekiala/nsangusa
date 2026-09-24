const requestHeaders = new Set([
  "accept", "accept-language", "authorization", "content-type", "content-encoding", "cookie", "origin", "referer",
  "user-agent", "x-xsrf-token", "x-csrf-token", "idempotency-key", "if-match", "if-none-match",
  "if-modified-since", "if-unmodified-since", "range", "if-range",
  "access-control-request-method", "access-control-request-headers",
  "svix-id", "svix-timestamp", "svix-signature", "x-webhook-signature"
]);
const hopByHop = new Set([
  "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer",
  "transfer-encoding", "upgrade"
]);
const frameworkHeader = (name: string) => /^(?:x-(?:middleware|nextjs|next|invoke)-|next-|rsc$)/.test(name);

function backendOrigin() {
  const value = process.env.NSANGUSA_API_URL || "http://localhost:8080";
  const url = new URL(value);
  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password
    || url.pathname !== "/" || url.search || url.hash || /[\s*';]/.test(value + url.origin)) {
    throw new Error("NSANGUSA_API_URL must be an explicit HTTP(S) origin.");
  }
  return url.origin;
}

function problem(status: number, title: string) {
  return Response.json({ title, status }, { status, headers: {
    "Cache-Control": "private, no-store", "Content-Type": "application/problem+json"
  } });
}

function apiPath(path: string) {
  return path === "/api/v1" || path.startsWith("/api/v1/");
}

export async function proxyApi(request: Request): Promise<Response> {
  let origin: string;
  try { origin = backendOrigin(); }
  catch { return problem(503, "API configuration unavailable"); }
  const incoming = new URL(request.url);
  const target = new URL(origin);
  target.pathname = incoming.pathname;
  target.search = incoming.search;
  if (!apiPath(target.pathname) || /%2f|%5c|\\/i.test(target.pathname)) return problem(400, "Invalid API path");

  const connectionHeaders = new Set((request.headers.get("connection") || "").toLowerCase().split(",").map((value) => value.trim()));
  const headers = new Headers();
  for (const [name, value] of request.headers) {
    if (requestHeaders.has(name) && !connectionHeaders.has(name)) headers.set(name, value);
  }
  // Fetch decodes compressed responses; identity avoids unnecessary recompression.
  headers.set("accept-encoding", "identity");
  const options: NonNullable<Parameters<typeof fetch>[1]> & { duplex?: "half" } = {
    method: request.method, headers, redirect: "manual", cache: "no-store",
    signal: AbortSignal.any([request.signal, AbortSignal.timeout(30_000)])
  };
  if (request.method !== "GET" && request.method !== "HEAD" && request.body) {
    options.body = request.body;
    options.duplex = "half";
  }
  try {
    // No redirect following or retry: a failed mutation may already have committed.
    const upstream = await fetch(target, options);
    const responseHeaders = new Headers();
    const upstreamConnection = new Set((upstream.headers.get("connection") || "").toLowerCase().split(",").map((value) => value.trim()));
    for (const [name, value] of upstream.headers) {
      if (!hopByHop.has(name) && !upstreamConnection.has(name) && !frameworkHeader(name)
        && !["set-cookie", "content-length", "content-encoding"].includes(name)) responseHeaders.set(name, value);
    }
    if (!upstreamConnection.has("set-cookie")) {
      for (const cookie of upstream.headers.getSetCookie()) responseHeaders.append("set-cookie", cookie);
    }
    const location = responseHeaders.get("location");
    if (location) {
      const redirect = new URL(location, target);
      if (redirect.origin === origin) responseHeaders.set("location", `${redirect.pathname}${redirect.search}${redirect.hash}`);
    }
    responseHeaders.set("cache-control", "private, no-store");
    return new Response(request.method === "HEAD" || [204, 205, 304].includes(upstream.status) ? null : upstream.body, {
      status: upstream.status, statusText: upstream.statusText, headers: responseHeaders
    });
  } catch {
    return problem(502, "API service unavailable");
  }
}
