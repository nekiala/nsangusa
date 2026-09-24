import { describe, expect, it } from "vitest";
import { contentError, contentFromBody, contentPlainText, resolvedContent, safeContentLink, type ArticleContent } from "./article-content";

const content: ArticleContent = { version: 1, blocks: [
  { type: "heading", text: "Heading" }, { type: "paragraph", text: "<b>Literal</b>" },
  { type: "quote", text: "Quoted words" }, { type: "unordered_list", items: ["One", "Two"] },
  { type: "ordered_list", items: ["Three", "Four"] },
  { type: "link", text: "Evidence", url: "https://example.test/report?a=1&b=2" }
] };

describe("structured article content", () => {
  it("derives a bounded canonical body without discarding text or link targets", () => {
    expect(contentError(content)).toBeNull();
    expect(contentPlainText(content)).toBe("Heading\n\n<b>Literal</b>\n\nQuoted words\n\nOne\nTwo\n\nThree\nFour\n\nEvidence (https://example.test/report?a=1&b=2)");
    expect(resolvedContent(content, "Ignored")).toEqual(content);
  });

  it("accepts unused null fields without treating text blocks as lists", () => {
    const serialized: ArticleContent = { version: 1, blocks: [
      { type: "heading", text: "Heading", items: null, url: null },
      { type: "ordered_list", text: null, items: ["Item"], url: null },
      { type: "link", text: "Evidence", items: null, url: "https://example.test" }
    ] };
    expect(contentError(serialized)).toBeNull();
    expect(contentPlainText(serialized)).toBe("Heading\n\nItem\n\nEvidence (https://example.test)");
  });

  it("converts legacy paragraphs without silently truncating long articles", () => {
    const body = Array.from({ length: 250 }, (_, index) => `Paragraph ${index}`).join("\n\n");
    const migrated = contentFromBody(body);
    expect(migrated.blocks).toHaveLength(200);
    expect(contentPlainText(migrated)).toBe(body);
    expect(resolvedContent(null, "First\n\nSecond").blocks).toEqual([
      { type: "paragraph", text: "First" }, { type: "paragraph", text: "Second" }
    ]);
  });

  it.each(["javascript:alert(1)", "data:text/html,unsafe", "/relative", "//example.test",
    "https://user:password@example.test", "https://@example.test", "https://example.test/\npath",
    "https://example.test/%0D%0apath", "https://example.test\\@evil.test", " https://example.test", "http:example.test", "https://example.test:99999"])("rejects unsafe link %s", (url) => {
    expect(safeContentLink(url)).toBe(false);
    expect(contentError({ version: 1, blocks: [{ type: "link", text: "Link", url }] })).toMatch(/HTTP/);
  });

  it.each([
    { version: 2, blocks: content.blocks }, { version: 1, blocks: [] },
    { version: 1, blocks: [null] }, { version: 1, blocks: [{ type: "embed", url: "https://example.test" }] },
    { version: 1, blocks: [{ type: "paragraph", text: "Text", html: "<b>ignored</b>" }] },
    { version: 1, blocks: [{ type: "paragraph", text: "Text", html: null }] },
    { version: 1, blocks: [{ type: "paragraph", text: 42 }] },
    { version: 1, blocks: [{ type: "heading", text: "Text", level: 1 }] },
    { version: 1, blocks: [{ type: "unordered_list", items: [""] }] },
    { version: 1, blocks: [{ type: "ordered_list", items: Array(101).fill("Item") }] },
    { version: 1, blocks: Array(201).fill({ type: "paragraph", text: "Text" }) },
    { version: 1, blocks: Array(2).fill({ type: "paragraph", text: "x".repeat(50000) }) }
  ])("rejects invalid nested content and limits", (value) => {
    expect(contentError(value)).not.toBeNull();
    expect(() => resolvedContent(value, "Legacy body")).toThrow();
  });
});
