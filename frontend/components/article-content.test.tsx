import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ArticleContentView } from "./article-content";

describe("safe article content renderer", () => {
  it("uses semantic React elements and escapes literal text and URL attributes", () => {
    const { container } = render(<ArticleContentView content={{ version: 1, blocks: [
      { type: "heading", text: "Section heading" },
      { type: "paragraph", text: '<script>alert("x")</script>' },
      { type: "quote", text: "<em>Quoted literally</em>" },
      { type: "unordered_list", items: ["Unordered one", "Unordered two"] },
      { type: "ordered_list", items: ["Ordered one", "Ordered two"] },
      { type: "link", text: "Primary evidence", url: "https://example.test/report?q=%22%20onclick%3D%22&a=1&b=2" }
    ] }} />);
    expect(screen.getByRole("heading", { level: 2, name: "Section heading" })).toBeVisible();
    expect(container.querySelector("blockquote")).toHaveTextContent("<em>Quoted literally</em>");
    expect(container.querySelector("ul")).toHaveTextContent("Unordered one");
    expect(container.querySelector("ol")).toHaveTextContent("Ordered two");
    expect(container.querySelectorAll("script, em, iframe")).toHaveLength(0);
    expect(screen.getByRole("link", { name: "Primary evidence" })).not.toHaveAttribute("onclick");
  });

  it("rejects malformed supplied content rather than silently rendering a different body", () => {
    expect(() => render(<ArticleContentView content={{ version: 1, blocks: [
      { type: "link", text: "Unsafe", url: "javascript:alert(1)" }
    ] }} body="<b>Stored plain body</b>" />)).toThrow(/absolute HTTP/);
    expect(document.querySelector("a, b, script, iframe")).toBeNull();
  });
});
