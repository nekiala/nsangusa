export function trustedApiOrigin(apiUrl: string): string | undefined {
  if (!apiUrl) return undefined;
  const url = new URL(apiUrl);
  if (/[\s*';]/.test(apiUrl + url.origin) || !["http:", "https:"].includes(url.protocol) || url.username || url.password
    || url.pathname !== "/" || url.search || url.hash) {
    throw new Error("NEXT_PUBLIC_API_URL must be empty (same-origin) or an explicit HTTP(S) origin.");
  }
  return url.origin;
}

export function contentSecurityPolicy(nonce: string, {
  apiUrl = "", development = false, origin
}: { apiUrl?: string; development?: boolean; origin?: string } = {}) {
  if (!/^[A-Za-z0-9+/=_-]+$/.test(nonce)) throw new Error("Invalid CSP nonce.");
  const apiOrigin = trustedApiOrigin(apiUrl);
  const connections = ["'self'", ...(apiOrigin ? [apiOrigin] : [])];
  if (development && origin) {
    const websocket = new URL(origin);
    websocket.protocol = websocket.protocol === "https:" ? "wss:" : "ws:";
    connections.push(websocket.origin);
  }
  return [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${development ? " 'unsafe-eval'" : ""}`,
    "script-src-attr 'none'",
    `style-src 'self' 'nonce-${nonce}'`,
    // React and Next use element style attributes, never inline event handlers.
    "style-src-attr 'unsafe-inline'",
    `img-src 'self' blob: data:${apiOrigin ? ` ${apiOrigin}` : ""}`,
    "font-src 'self'",
    `connect-src ${connections.join(" ")}`,
    "object-src 'none'",
    "base-uri 'none'",
    "form-action 'self'",
    "frame-ancestors 'none'",
    "frame-src 'none'"
  ].join("; ");
}
