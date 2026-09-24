// @vitest-environment node
import { afterEach, describe, expect, it, vi } from "vitest";
import { proxyApi } from "./runtime-api-proxy";

afterEach(() => { vi.unstubAllEnvs(); vi.unstubAllGlobals(); });

describe("runtime same-origin API proxy", () => {
  it("reads the backend origin at request time, ignoring the incoming hostname and destination headers", async () => {
    const fetcher = vi.fn().mockImplementation(async () => Response.json({ available: true }));
    vi.stubGlobal("fetch", fetcher);
    vi.stubEnv("NSANGUSA_API_URL", "http://runtime-backend:8080");
    const request = new Request("http://untrusted-host.test/api/v1/search?q=library%20room&page=2", {
      headers: { "x-forwarded-host": "attacker.test", "x-original-url": "http://attacker.test/private" }
    });
    expect((await proxyApi(request)).status).toBe(200);
    expect(String(fetcher.mock.calls[0][0])).toBe("http://runtime-backend:8080/api/v1/search?q=library%20room&page=2");
    vi.stubEnv("NSANGUSA_API_URL", "https://second-runtime-backend.test");
    await proxyApi(request);
    expect(String(fetcher.mock.calls[1][0])).toBe("https://second-runtime-backend.test/api/v1/search?q=library%20room&page=2");
  });

  it.each(["file:///private", "https://user:secret@backend.test", "http://*.backend.test",
    "http://backend.test/path", "http://backend.test/?url=evil", "http://backend.test/#fragment"])("fails closed for unsafe operator configuration %s", async (origin) => {
    vi.stubEnv("NSANGUSA_API_URL", origin);
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    const result = await proxyApi(new Request("http://frontend.test/api/v1/auth/me"));
    expect(result.status).toBe(503);
    expect(await result.text()).not.toContain(origin);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it.each(["/actuator/health", "/api/v1/%2f..%2fprivate", "/api/v1/%5cprivate", "/api/v1/../../actuator/health"])("rejects paths outside the API namespace: %s", async (path) => {
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    expect((await proxyApi(new Request(`http://frontend.test${path}`))).status).toBe(400);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("streams a mutation once with session, CSRF and idempotency state, without forwarding trusted-proxy/framework headers", async () => {
    let received = "";
    const fetcher = vi.fn(async (_url: URL, options: NonNullable<Parameters<typeof fetch>[1]> & { duplex: string }) => {
      received = await new Response(options.body).text();
      return new Response(null, { status: 204 });
    });
    vi.stubGlobal("fetch", fetcher);
    const request = new Request("http://frontend.test/api/v1/auth/login", {
      method: "POST", body: "username=reader%40example.test&password=local-test-password", headers: {
        "content-type": "application/x-www-form-urlencoded", cookie: "SESSION=opaque; XSRF-TOKEN=csrf",
        "x-xsrf-token": "csrf", "idempotency-key": "unique-request", authorization: "Basic test",
        "x-forwarded-for": "1.2.3.4", "x-forwarded-proto": "https", forwarded: "for=1.2.3.4",
        "x-real-ip": "1.2.3.4", host: "attacker.test", "x-user-id": "administrator",
        "x-middleware-subrequest": "middleware:middleware", "x-middleware-rewrite": "https://attacker.test",
        connection: "keep-alive, x-csrf-token", "x-csrf-token": "hop-by-hop",
        "x-nextjs-data": "1", rsc: "1"
      }
    });
    const result = await proxyApi(request);
    expect(result.status).toBe(204);
    expect(await result.text()).toBe("");
    expect(received).toBe("username=reader%40example.test&password=local-test-password");
    expect(fetcher).toHaveBeenCalledTimes(1);
    const options = fetcher.mock.calls[0][1];
    const headers = options.headers as Headers;
    expect(options).toMatchObject({ method: "POST", body: request.body, duplex: "half", redirect: "manual", cache: "no-store" });
    expect(headers.get("cookie")).toBe("SESSION=opaque; XSRF-TOKEN=csrf");
    expect(headers.get("x-xsrf-token")).toBe("csrf");
    expect(headers.get("idempotency-key")).toBe("unique-request");
    expect(headers.get("authorization")).toBe("Basic test");
    for (const name of ["host", "connection", "x-csrf-token", "forwarded", "x-forwarded-for",
      "x-forwarded-proto", "x-real-ip", "x-user-id", "x-middleware-subrequest", "x-middleware-rewrite", "x-nextjs-data", "rsc"]) {
      expect(headers.has(name), name).toBe(false);
    }
  });

  it("preserves separate Set-Cookie values, status, retry and download headers while removing transport/framework metadata", async () => {
    const headers = new Headers({
      "content-type": "application/problem+json", "retry-after": "60", "content-encoding": "gzip",
      "content-length": "999", "content-disposition": 'attachment; filename="account.json"',
      connection: "keep-alive, x-private-hop", "x-private-hop": "drop", "keep-alive": "timeout=5",
      "x-middleware-rewrite": "https://attacker.test", "x-middleware-set-cookie": "admin=true",
      "x-nextjs-redirect": "https://attacker.test"
    });
    const cookies = ["SESSION=opaque; Path=/; HttpOnly; SameSite=Lax",
      "XSRF-TOKEN=token; Expires=Wed, 21 Oct 2030 07:28:00 GMT; Path=/; SameSite=Lax"];
    for (const value of cookies) headers.append("set-cookie", value);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response('{"status":429}', { status: 429, headers })));
    const result = await proxyApi(new Request("http://frontend.test/api/v1/auth/me"));
    expect(result.status).toBe(429);
    expect(result.headers.getSetCookie()).toEqual(cookies);
    expect(result.headers.get("retry-after")).toBe("60");
    expect(result.headers.get("content-disposition")).toBe('attachment; filename="account.json"');
    expect(result.headers.get("cache-control")).toContain("no-store");
    for (const name of ["content-length", "content-encoding", "connection", "keep-alive", "x-private-hop",
      "x-middleware-rewrite", "x-middleware-set-cookie", "x-nextjs-redirect"]) expect(result.headers.has(name)).toBe(false);
    expect(await result.json()).toEqual({ status: 429 });
  });

  it("returns body chunks before the upstream stream finishes", async () => {
    let source!: ReadableStreamDefaultController<Uint8Array>;
    const stream = new ReadableStream<Uint8Array>({ start(controller) {
      source = controller;
      controller.enqueue(new Uint8Array([1, 2, 3]));
    } });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(stream, {
      status: 206, headers: { "content-type": "image/png", "content-range": "bytes 0-5/6" }
    })));
    const result = await proxyApi(new Request("http://frontend.test/api/v1/articles/fixture/image", { headers: { range: "bytes=0-5" } }));
    expect(result.status).toBe(206);
    expect(result.headers.get("content-range")).toBe("bytes 0-5/6");
    const reader = result.body!.getReader();
    expect(await reader.read()).toEqual({ value: new Uint8Array([1, 2, 3]), done: false });
    source.enqueue(new Uint8Array([4, 5, 6]));
    source.close();
    expect(await reader.read()).toEqual({ value: new Uint8Array([4, 5, 6]), done: false });
    expect((await reader.read()).done).toBe(true);
  });

  it.each([["http://backend.test:8080/api/v1/auth/me?next=1", "/api/v1/auth/me?next=1"],
    ["https://identity.example.test/authorize", "https://identity.example.test/authorize"]])("preserves redirects without following upstream Location %s", async (location, expected) => {
    vi.stubEnv("NSANGUSA_API_URL", "http://backend.test:8080");
    const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 303, headers: { location } }));
    vi.stubGlobal("fetch", fetcher);
    const result = await proxyApi(new Request("http://frontend.test/api/v1/auth/login", { method: "POST", body: "login" }));
    expect(result.status).toBe(303);
    expect(result.headers.get("location")).toBe(expected);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[0][1].redirect).toBe("manual");
  });

  it.each([["HEAD", 200], ["GET", 304], ["DELETE", 204], ["OPTIONS", 204]] as const)("handles bodyless %s / %s responses", async (method, status) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status })));
    const response = await proxyApi(new Request("http://frontend.test/api/v1/auth/me", { method }));
    expect(response.status).toBe(status);
    expect(response.body).toBeNull();
  });

  it("propagates cancellation and returns a bounded generic failure without retrying an uncertain mutation", async () => {
    const controller = new AbortController();
    const fetcher = vi.fn().mockRejectedValue(new Error("secret backend.internal:8080 connection failure"));
    vi.stubGlobal("fetch", fetcher);
    const response = await proxyApi(new Request("http://frontend.test/api/v1/admin/articles", {
      method: "POST", body: "{}", signal: controller.signal
    }));
    controller.abort();
    expect(fetcher.mock.calls[0][1].signal.aborted).toBe(true);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(response.status).toBe(502);
    expect(await response.json()).toEqual({ title: "API service unavailable", status: 502 });
  });
});
