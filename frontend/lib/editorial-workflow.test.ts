import { describe, expect, it } from "vitest";
import { createApiClient, type ArticleCommand } from "./api";

const article: ArticleCommand = {
  headline: "Editorial workflow", summary: "A sourced article for review.", body: "A complete body.",
  seoTitle: "Editorial workflow", seoDescription: "A sourced article for review.", slugSuggestion: "editorial-workflow",
  topic: "Ideas", tags: ["Workflow"], commentsEnabled: true,
  sources: [{ sourcePostId: "00000000-0000-4000-8000-000000000001", account: "@source", postId: "post", url: "https://example.invalid/source", publishedAt: "2026-09-01T00:00:00.000Z" }]
};

describe("editorial workflow fake mode", () => {
  it("creates, edits, approves, schedules, publishes, unpublishes, restores, rejects and archives an article", async () => {
    const client = createApiClient({ mode: "fake" });
    const { id } = await client.admin.createArticle(article);
    await client.admin.editArticle(id, 1, { ...article, headline: "Edited workflow" });
    expect((await client.admin.article(id)).state).toBe("AWAITING_REVIEW");
    await client.admin.approveArticle(id);
    expect((await client.admin.article(id)).state).toBe("APPROVED");
    await client.admin.scheduleArticle(id, "2027-01-01T00:00:00.000Z");
    expect((await client.admin.article(id)).state).toBe("SCHEDULED");
    await client.admin.publishArticle(id);
    expect((await client.articles.bySlug(article.slugSuggestion)).state).toBe("PUBLISHED");
    await client.admin.unpublishArticle(id);
    await expect(client.articles.bySlug(article.slugSuggestion)).rejects.toMatchObject({ status: 404 });
    await client.admin.restoreArticle(id);
    await client.admin.unpublishArticle(id);
    await client.admin.rejectArticle(id);
    await client.admin.archiveArticle(id);
    expect((await client.admin.article(id)).state).toBe("ARCHIVED");
  });
});
