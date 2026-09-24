export function publicBaseUrl() {
  const value = process.env.PUBLIC_BASE_URL || process.env.NEXT_PUBLIC_SITE_URL || "http://localhost:3000";
  const url = new URL(value);
  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password
    || url.pathname !== "/" || url.search || url.hash || /[\s*';]/.test(value + url.origin)) {
    throw new Error("The publication base URL must be an explicit HTTP(S) origin.");
  }
  return url.origin;
}
