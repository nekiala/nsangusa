"use client";

import { useLocale } from "@/components/locale";
import { pageHref } from "@/lib/public-pagination";

export function Pagination({ page, size, total, path, query, maximumPage = 10_001 }: {
  page: number; size: number; total: number; path: string; query?: string; maximumPage?: number;
}) {
  const { t, path: localized } = useLocale();
  const pages = Math.ceil(total / size);
  if (pages < 2) return null;
  const href = (target: number) => localized(pageHref(path, target, size, query));
  // Fresh document navigation keeps uncached results and their canonical metadata together.
  return <nav className="section meta-list" aria-label={t("pagination.label")}>
    {page > 0 && <a rel="prev" href={href(page - 1)}>{t("pagination.previous")}</a>}
    <span aria-current="page">{t("pagination.position", { page: page + 1, pages })}</span>
    {page + 1 < pages && page + 1 < maximumPage && <a rel="next" href={href(page + 1)}>{t("pagination.next")}</a>}
    {page + 1 === maximumPage && pages > maximumPage && <p>{t("pagination.narrow")}</p>}
  </nav>;
}
