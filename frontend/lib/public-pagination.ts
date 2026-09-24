export type SearchParams = Record<string, string | string[] | undefined>;

export function decodeFacetSegment(value: string) {
  try { return decodeURIComponent(value).trim().toLowerCase(); }
  catch { return undefined; }
}

export function readPagination(params: SearchParams, maximumPage = 10_001) {
  const page = params.page ?? "1";
  const size = params.size ?? "20";
  if (typeof page !== "string" || typeof size !== "string" || !/^[1-9]\d*$/.test(page) || !/^[1-9]\d*$/.test(size)) return undefined;
  const parsedPage = Number(page);
  const parsedSize = Number(size);
  if (!Number.isSafeInteger(parsedPage) || parsedPage > maximumPage || !Number.isSafeInteger(parsedSize) || parsedSize > 100) return undefined;
  return { page: parsedPage - 1, size: parsedSize };
}

export function pageHref(path: string, page: number, size = 20, query?: string) {
  const params = new URLSearchParams();
  if (query) params.set("q", query);
  if (page > 0) params.set("page", String(page + 1));
  if (size !== 20) params.set("size", String(size));
  return `${path}${params.size ? `?${params}` : ""}`;
}
