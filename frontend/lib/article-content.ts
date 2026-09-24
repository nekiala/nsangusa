type TextFields = { text: string; items?: null; url?: null };
type ListFields = { items: string[]; text?: null; url?: null };
export type ArticleBlock =
  | ({ type: "paragraph" } & TextFields)
  | ({ type: "heading" } & TextFields)
  | ({ type: "quote" } & TextFields)
  | ({ type: "unordered_list" } & ListFields)
  | ({ type: "ordered_list" } & ListFields)
  | { type: "link"; text: string; url: string; items?: null };
export type ArticleContent = { version: 1; blocks: ArticleBlock[] };

export const MAX_CONTENT_BLOCKS = 200;
export const MAX_CONTENT_TEXT = 100000;

export function safeContentLink(value: string): boolean {
  if (value.length > 2048 || !/^https?:\/\//i.test(value)
    || /[\s\\]/.test(value) || Array.from(value).some((character) => {
      const code = character.codePointAt(0)!;
      return code <= 31 || (code >= 127 && code <= 159);
    })
    || /%(?:0[0-9a-f]|1[0-9a-f]|7f)/i.test(value)) return false;
  try {
    const url = new URL(value);
    return !!url.hostname && !url.username && !url.password
      && !value.slice(value.indexOf("//") + 2).split(/[/?#]/, 1)[0].includes("@");
  } catch { return false; }
}

export function contentPlainText(content: ArticleContent): string {
  return content.blocks.map((block) => {
    if (block.type === "unordered_list" || block.type === "ordered_list") return block.items.map((item) => item.trim()).join("\n");
    if (block.type === "link") return `${block.text.trim()} (${block.url})`;
    return block.text.trim();
  }).join("\n\n");
}

export function contentFromBody(body: string): ArticleContent {
  const paragraphs = body.trim().split(/\n\s*\n/).filter((text) => text.trim());
  const bounded = paragraphs.length > MAX_CONTENT_BLOCKS
    ? [...paragraphs.slice(0, MAX_CONTENT_BLOCKS - 1), paragraphs.slice(MAX_CONTENT_BLOCKS - 1).join("\n\n")]
    : paragraphs;
  return { version: 1, blocks: bounded.map((text) => ({ type: "paragraph", text })) };
}

function object(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}
const nonblank = (value: unknown): value is string => typeof value === "string" && !!value.trim() && value.length <= MAX_CONTENT_TEXT;

export function contentError(value: unknown): string | null {
  if (!object(value) || value.version !== 1 || Object.keys(value).some((key) => !["version", "blocks"].includes(key))) {
    return "Unsupported article content format.";
  }
  if (!Array.isArray(value.blocks) || value.blocks.length < 1 || value.blocks.length > MAX_CONTENT_BLOCKS) {
    return "Use between 1 and 200 content blocks.";
  }
  let total = (value.blocks.length - 1) * 2;
  for (let index = 0; index < value.blocks.length; index++) {
    const block = value.blocks[index];
    const prefix = `Block ${index + 1}: `;
    if (!object(block)) return prefix + "choose a supported block.";
    if (Object.keys(block).some((key) => !["type", "text", "items", "url"].includes(key))) return prefix + "unsupported block fields.";
    if (block.type === "unordered_list" || block.type === "ordered_list") {
      if (block.text != null || block.url != null) return prefix + "lists can contain only items.";
      if (!Array.isArray(block.items) || !block.items.length || block.items.length > 100 || !block.items.every(nonblank)) {
        return prefix + "enter between 1 and 100 nonblank list items.";
      }
      total += block.items.reduce((sum: number, item: string) => sum + item.trim().length, 0) + block.items.length - 1;
    } else if (["paragraph", "heading", "quote", "link"].includes(String(block.type))) {
      const keys = block.type === "link" ? ["type", "text", "url"] : ["type", "text"];
      if (Object.keys(block).some((key) => !keys.includes(key) && block[key] != null)) return prefix + "unsupported block fields.";
      if (!nonblank(block.text)) return prefix + "enter text (at most 100000 characters).";
      if (block.type === "link" && (typeof block.url !== "string" || !safeContentLink(block.url))) {
        return prefix + "enter an absolute HTTP(S) link without credentials, spaces or control characters.";
      }
      total += block.text.trim().length + (block.type === "link" ? (block.url as string).length + 3 : 0);
    } else return prefix + "choose a supported block type.";
    if (total > MAX_CONTENT_TEXT) return "Article content must be at most 100000 characters in total.";
  }
  return null;
}

export function resolvedContent(content: unknown, body: string): ArticleContent {
  if (content == null) return contentFromBody(body);
  const error = contentError(content);
  if (error) throw new Error(error);
  return content as ArticleContent;
}
