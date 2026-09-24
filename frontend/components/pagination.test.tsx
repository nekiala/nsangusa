import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Pagination } from "./pagination";
import { decodeFacetSegment, pageHref, readPagination } from "@/lib/public-pagination";

describe("public pagination", () => {
  it("keeps the search and page size on accessible previous and next links", () => {
    render(<Pagination path="/search" query="culture & cities" page={1} size={10} total={32} />);
    expect(screen.getByRole("navigation", { name: "Pagination" })).toHaveTextContent("Page 2 of 4");
    expect(screen.getByRole("link", { name: "Previous page" })).toHaveAttribute("href", "/search?q=culture+%26+cities&size=10");
    expect(screen.getByRole("link", { name: "Next page" })).toHaveAttribute("href", "/search?q=culture+%26+cities&page=3&size=10");
  });
  it("has no next link on the final page", () => {
    render(<Pagination path="/latest" page={2} size={20} total={45} />);
    expect(screen.queryByRole("link", { name: "Next page" })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Previous page" })).toHaveAttribute("href", "/latest?page=2");
  });
  it("rejects malformed, repeated, negative, oversized and out-of-range page parameters", () => {
    for (const page of ["0", "-1", "1.5", "1e2", "10002", ["1", "2"]]) expect(readPagination({ page })).toBeUndefined();
    for (const size of ["0", "101", "-2", "NaN", ["20", "40"]]) expect(readPagination({ size })).toBeUndefined();
    expect(readPagination({ page: "2", size: "100" })).toEqual({ page: 1, size: 100 });
    expect(pageHref("/topics/public%20life", 0)).toBe("/topics/public%20life");
    expect(decodeFacetSegment("Public%20life")).toBe("public life");
    expect(decodeFacetSegment("100%25")).toBe("100%");
    expect(decodeFacetSegment("%broken")).toBeUndefined();
  });
});
