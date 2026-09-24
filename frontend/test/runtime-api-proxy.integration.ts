import { spawn, type ChildProcess } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { once } from "node:events";
import { readFile } from "node:fs/promises";
import { createServer, type Server } from "node:http";
import { resolve } from "node:path";
import { setTimeout as pause } from "node:timers/promises";
import { expect, test } from "vitest";
import type { ApiArticle } from "../lib/api";

async function listen(server: Server) {
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("Expected an owned TCP listener");
  return `http://127.0.0.1:${address.port}`;
}

async function closeServer(server: Server) {
  server.closeAllConnections();
  if (server.listening) await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
}

async function stop(child: ChildProcess) {
  if (child.exitCode !== null || child.signalCode !== null) return;
  const exited = once(child, "exit");
  child.kill("SIGTERM");
  await Promise.race([exited, pause(5000, undefined, { ref: false })]);
  if (child.exitCode === null && child.signalCode === null) { child.kill("SIGKILL"); await exited; }
}

async function startFrontend(backend: string) {
  const reservation = createServer();
  const origin = await listen(reservation);
  await closeServer(reservation);
  const child = spawn(process.execPath, [resolve(".next-runtime-proxy/standalone/server.js")], {
    env: { ...process.env, NSANGUSA_API_URL: backend, PUBLIC_BASE_URL: origin, HOSTNAME: "127.0.0.1", PORT: new URL(origin).port, NODE_ENV: "production" },
    stdio: ["ignore", "pipe", "pipe"]
  });
  let output = "";
  try {
    await new Promise<void>((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error(`Owned frontend did not become ready: ${output}`)), 10_000);
      const failed = (error: Error) => { clearTimeout(timeout); reject(error); };
      child.once("error", failed);
      child.once("exit", (code) => failed(new Error(`Owned frontend exited (${code}): ${output}`)));
      child.stdout.on("data", (data: Buffer) => {
        output = `${output}${data.toString()}`.slice(-16000);
        if (output.includes("Ready in")) { clearTimeout(timeout); resolve(); }
      });
      child.stderr.on("data", (data: Buffer) => { output = `${output}${data.toString()}`.slice(-16000); });
    });
    return { origin, close: () => stop(child) };
  } catch (error) {
    await stop(child);
    throw error;
  }
}

async function artifactHash() {
  const hash = createHash("sha256");
  for (const file of ["server.js", ".next-runtime-proxy/BUILD_ID", ".next-runtime-proxy/server/app/api/v1/[[...path]]/route.js"]) {
    hash.update(await readFile(resolve(".next-runtime-proxy/standalone", file)));
  }
  return hash.digest("hex");
}

test("one production artifact uses two runtime API/public origins with correct metadata, feeds and streamed API semantics", async () => {
  const markers = [randomUUID(), randomUUID()];
  const requests: { marker: string; path: string; headers: import("node:http").IncomingHttpHeaders; body: string }[] = [];
  let finishStream: (() => void) | undefined;
  const backends = await Promise.all(markers.map(async (marker) => {
    const article: ApiArticle = {
      id: marker, slug: `runtime-${marker}`, headline: `Runtime article ${marker}`, summary: "An owned runtime fixture.",
      body: "Source-backed runtime fixture.", editorialContext: null, topic: "Ideas", tags: [],
      state: "PUBLISHED", heroObjectKey: "owned-fixture-image", imageAltText: "Fixture image",
      generatedImage: false, commentsEnabled: false, publishedAt: "2026-09-01T12:00:00Z",
      updatedAt: "2026-09-01T12:00:00Z", version: 1, sources: [], warnings: [], confidence: 1,
      storyCandidateId: null, seoTitle: `Runtime article ${marker}`, seoDescription: "An owned runtime fixture.",
      approvedImageGenerationId: marker, pendingImageGenerationId: null, imageApprovalRequired: false,
      correctionNote: null, approvedBy: null, approvedAt: null
    };
    const server = createServer(async (request, response) => {
      let body = "";
      for await (const chunk of request) body += chunk.toString();
      requests.push({ marker, path: request.url!, headers: request.headers, body });
      if (request.url === "/api/v1/stream") {
        response.writeHead(206, { "content-type": "application/octet-stream", "content-range": "bytes 0-5/6" });
        response.write(new Uint8Array([1, 2, 3]));
        finishStream = () => response.end(new Uint8Array([4, 5, 6]));
      } else if (request.url === "/api/v1/redirect") {
        response.writeHead(303, { location: `${origin}/api/v1/never-follow` });
        response.end();
      } else {
        response.writeHead(request.method === "POST" ? 202 : 200, {
          "content-type": "application/json",
          "set-cookie": ["SESSION=runtime-session; Path=/; HttpOnly; SameSite=Lax",
            "XSRF-TOKEN=runtime-csrf; Expires=Wed, 21 Oct 2030 07:28:00 GMT; Path=/; SameSite=Lax"],
          "x-middleware-rewrite": "http://never-forward.invalid",
          "x-nextjs-redirect": "http://never-forward.invalid"
        });
        const url = new URL(request.url!, origin);
        const data = url.pathname === "/api/v1/topics" ? [{ value: marker, articleCount: 1 }]
          : url.pathname === "/api/v1/articles/discovery" ? { items: [article], page: 0, size: Number(url.searchParams.get("size") || "20"), total: 1 }
            : url.pathname === `/api/v1/articles/${article.slug}` ? article
              : url.pathname === `/api/v1/articles/${article.slug}/related` ? [] : { marker, body };
        response.end(JSON.stringify(data));
      }
    });
    const origin = await listen(server);
    return { origin, close: () => closeServer(server) };
  }));
  try {
    const before = await artifactHash();
    for (let index = 0; index < backends.length; index++) {
      const frontend = await startFrontend(backends[index].origin);
      try {
        const result = await fetch(`${frontend.origin}/api/v1/probe?origin=http://ignored.invalid`, {
          headers: {
            "x-forwarded-for": "1.2.3.4", "x-forwarded-host": "ignored.invalid", "x-forwarded-proto": "https",
            "x-middleware-subrequest": "proxy:proxy", "x-original-url": "http://ignored.invalid",
            cookie: "SESSION=runtime-session; XSRF-TOKEN=runtime-csrf"
          }
        });
        expect(await result.json()).toEqual({ marker: markers[index], body: "" });
        expect(result.headers.get("cache-control")).toContain("no-store");
        expect(result.headers.getSetCookie()).toEqual([
          "SESSION=runtime-session; Path=/; HttpOnly; SameSite=Lax",
          "XSRF-TOKEN=runtime-csrf; Expires=Wed, 21 Oct 2030 07:28:00 GMT; Path=/; SameSite=Lax"
        ]);
        expect(result.headers.has("x-middleware-rewrite")).toBe(false);
        expect(result.headers.has("x-nextjs-redirect")).toBe(false);
        const forwarded = requests.at(-1)!;
        const forwardedUrl = new URL(forwarded.path, backends[index].origin);
        expect(forwardedUrl.pathname).toBe("/api/v1/probe");
        expect(forwardedUrl.searchParams.get("origin")).toBe("http://ignored.invalid");
        expect(forwarded.headers.cookie).toContain("SESSION=runtime-session");
        for (const header of ["x-forwarded-for", "x-forwarded-host", "x-forwarded-proto",
          "x-middleware-subrequest", "x-original-url"]) expect(forwarded.headers[header]).toBeUndefined();
        expect(forwarded.headers.host).toBe(new URL(backends[index].origin).host);

        const mutation = await fetch(`${frontend.origin}/api/v1/auth/login`, {
          method: "POST", headers: {
            "content-type": "application/x-www-form-urlencoded", cookie: "SESSION=runtime-session",
            "x-xsrf-token": "runtime-csrf", "idempotency-key": "runtime-mutation"
          }, body: "username=reader%40example.test&password=local-fixture"
        });
        expect(mutation.status).toBe(202);
        expect(await mutation.json()).toEqual({
          marker: markers[index], body: "username=reader%40example.test&password=local-fixture"
        });
        expect(requests.at(-1)!.headers["x-xsrf-token"]).toBe("runtime-csrf");
        expect(requests.at(-1)!.headers["idempotency-key"]).toBe("runtime-mutation");
        expect(requests.filter((request) => request.marker === markers[index] && request.path === "/api/v1/auth/login")).toHaveLength(1);

        const streamed = await fetch(`${frontend.origin}/api/v1/stream`);
        expect(streamed.status).toBe(206);
        expect(streamed.headers.get("content-range")).toBe("bytes 0-5/6");
        const reader = streamed.body!.getReader();
        expect((await reader.read()).value).toEqual(new Uint8Array([1, 2, 3]));
        finishStream!();
        finishStream = undefined;
        expect((await reader.read()).value).toEqual(new Uint8Array([4, 5, 6]));
        expect((await reader.read()).done).toBe(true);

        const redirect = await fetch(`${frontend.origin}/api/v1/redirect`, { redirect: "manual" });
        expect(redirect.status).toBe(303);
        expect(redirect.headers.get("location")).toBe("/api/v1/never-follow");
        expect(requests.some((request) => request.path === "/api/v1/never-follow")).toBe(false);
        const rendered = await fetch(`${frontend.origin}/topics`, { headers: { "x-forwarded-host": "untrusted.invalid" } });
        expect(rendered.status).toBe(200);
        const html = await rendered.text();
        expect(html).toContain(markers[index]);
        expect(html).toContain(`rel="canonical" href="${frontend.origin}/topics"`);
        expect(html).not.toContain("build-time.invalid");
        expect(rendered.headers.get("content-security-policy")).toContain("'nonce-");
        const slug = `runtime-${markers[index]}`;
        const article = await fetch(`${frontend.origin}/articles/${slug}`);
        const articleHtml = await article.text();
        expect(article.status).toBe(200);
        expect(articleHtml).toContain(`rel="canonical" href="${frontend.origin}/articles/${slug}"`);
        expect(articleHtml).toContain(`property="og:url" content="${frontend.origin}/articles/${slug}"`);
        expect(articleHtml).toContain(`property="og:image" content="${frontend.origin}/api/v1/articles/${slug}/image?variant=social"`);
        const structured = JSON.parse(articleHtml.match(/<script type="application\/ld\+json">([^<]+)<\/script>/)![1]);
        expect(structured).toMatchObject({
          mainEntityOfPage: `${frontend.origin}/articles/${slug}`,
          image: `${frontend.origin}/api/v1/articles/${slug}/image?variant=hero`
        });
        for (const [path, expectedUrl] of [
          ["/robots.txt", "/sitemap.xml"],
          ["/sitemap.xml", "/sitemaps/articles/0.xml"],
          ["/sitemaps/static.xml", "/latest"],
          ["/sitemaps/articles/0.xml", `/articles/${slug}`],
          ["/rss.xml", `/articles/${slug}`]
        ]) {
          const feed = await fetch(`${frontend.origin}${path}`);
          expect(feed.status, path).toBe(200);
          expect(feed.headers.get("cache-control"), path).toContain("no-store");
          const body = await feed.text();
          expect(body, path).toContain(`${frontend.origin}${expectedUrl}`);
          expect(body, path).not.toContain("build-time.invalid");
        }
      } finally {
        finishStream?.();
        await frontend.close();
      }
    }
    expect(await artifactHash()).toBe(before);
  } finally {
    await Promise.all(backends.map((backend) => backend.close()));
  }
});
