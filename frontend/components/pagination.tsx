import { pageHref } from "@/lib/public-pagination";

export function Pagination({ page, size, total, path, query, maximumPage = 10_001 }: {
  page: number; size: number; total: number; path: string; query?: string; maximumPage?: number;
}) {
  const pages = Math.ceil(total / size);
  if (pages < 2) return null;
  // Fresh document navigation keeps uncached results and their canonical metadata together.
  return <nav className="section meta-list" aria-label="Pagination">
    {page > 0 && <a rel="prev" href={pageHref(path, page - 1, size, query)}>Previous page</a>}
    <span aria-current="page">Page {page + 1} of {pages}</span>
    {page + 1 < pages && page + 1 < maximumPage && <a rel="next" href={pageHref(path, page + 1, size, query)}>Next page</a>}
    {page + 1 === maximumPage && pages > maximumPage && <p>Narrow your search to browse more results.</p>}
  </nav>;
}
