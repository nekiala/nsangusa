import { describe, expect, it } from "vitest";
import { createApiClient, type ArticleCommand } from "./api";

const article: ArticleCommand = {
  headline: "Editorial workflow", summary: "A sourced article for review.", body: "A complete body.",
  seoTitle: "Editorial workflow", seoDescription: "A sourced article for review.", slugSuggestion: "editorial-workflow",
  topic: "Ideas", tags: ["Workflow"], commentsEnabled: true,
  sources: [{ sourcePostId: "00000000-0000-4000-8000-000000000001", account: "@source", postId: "post", url: "https://example.invalid/source", publishedAt: "2026-09-01T00:00:00.000Z" }]
};

describe("editorial workflow fake mode", () => {
  it("stores the clean canonical URL when simulating a shared X post", async () => {
    const client = createApiClient({ mode: "fake" });
    const account = await client.admin.addXAccount({
      accountId: "123456", handle: "BCC_RDC", displayName: "BCC", topics: ["Economy"], relevanceThreshold: 0.5
    });
    const canonicalUrl = "https://x.com/BCC_RDC/status/2101913409423876230";
    const { id } = await client.admin.simulateXPost(account.id, {
      postId: "2101913409423876230", canonicalUrl: `${canonicalUrl}?s=20&t=shared#replies`,
      permittedText: "Indicative daily exchange rates", publishedAt: "2026-01-01T00:00:00Z"
    });
    expect(await client.admin.source(id)).toMatchObject({ canonicalUrl, postId: "2101913409423876230" });
  });

  it("rejects mismatched share links and future publication times without creating sources", async () => {
    const client = createApiClient({ mode: "fake" });
    const account = await client.admin.addXAccount({
      accountId: "123457", handle: "BCC_RDC", displayName: "BCC", topics: ["Economy"], relevanceThreshold: 0.5
    });
    const original = (await client.admin.sources()).length;
    const input = { postId: "2101913409423876230", permittedText: "Indicative daily exchange rates",
      canonicalUrl: "https://x.com/BCC_RDC/status/2101913409423876230", publishedAt: "2026-01-01T00:00:00Z" };
    for (const canonicalUrl of [
      "http://x.com/BCC_RDC/status/2101913409423876230?s=20",
      "https://x.com.example.test/BCC_RDC/status/2101913409423876230?s=20",
      "https://user@x.com/BCC_RDC/status/2101913409423876230?s=20",
      "https://x.com:443/BCC_RDC/status/2101913409423876230?s=20",
      "https://x.com/other/status/2101913409423876230?s=20",
      "https://x.com/BCC_RDC/status/2101913409423876231?s=20"
    ]) {
      await expect(client.admin.simulateXPost(account.id, { ...input, canonicalUrl })).rejects.toMatchObject({ status: 400 });
    }
    await expect(client.admin.simulateXPost(account.id, {
      ...input, publishedAt: new Date(Date.now() + 86_400_000).toISOString()
    })).rejects.toMatchObject({ status: 400, problem: { detail: "A valid X publication timestamp is required" } });
    expect(await client.admin.sources()).toHaveLength(original);
  });

  it("round trips structured content, derives authoritative text and preserves earlier revision blocks", async () => {
    const client = createApiClient({ mode: "fake" });
    const input: ArticleCommand = { ...article, slugSuggestion: "structured-workflow", body: "Ignored plain body",
      content: { version: 1, blocks: [{ type: "heading", text: "Structured heading" }, { type: "ordered_list", items: ["One", "Two"] }] } };
    const { id } = await client.admin.createArticle(input);
    expect(await client.admin.article(id)).toMatchObject({ body: "Structured heading\n\nOne\nTwo", content: input.content });
    const changed: ArticleCommand = { ...input, body: undefined, content: {
      version: 1, blocks: [{ type: "quote", text: "Corrected quote" }, { type: "link", text: "Evidence", url: "https://example.test/evidence" }]
    } };
    await client.admin.editArticle(id, 1, changed);
    const comparison = await client.admin.compareRevisions(id, 1, 2);
    expect(comparison.changedFields).toEqual(expect.arrayContaining(["body", "content"]));
    expect(comparison.from.snapshot?.content).toEqual(input.content);
    expect(comparison.to.snapshot?.content).toEqual(changed.content);
    await client.admin.approveArticle(id);
    await client.admin.publishArticle(id);
    expect(await client.articles.bySlug(input.slugSuggestion)).toMatchObject({
      content: changed.content, body: "Corrected quote\n\nEvidence (https://example.test/evidence)"
    });
  });

  it("creates, edits, approves, schedules, publishes, unpublishes, restores and archives an article", async () => {
    const client = createApiClient({ mode: "fake" });
    const available = await client.admin.sources();
    const source = await client.admin.source(available[0].id);
    const sourcedArticle = { ...article, sources: [{ sourcePostId: source.id, account: source.handle, postId: source.postId, url: source.canonicalUrl, publishedAt: source.publishedAt }] };
    const { id } = await client.admin.createArticle(sourcedArticle);
    await client.admin.editArticle(id, 1, { ...sourcedArticle, headline: "Edited workflow" });
    expect((await client.admin.article(id)).state).toBe("AWAITING_REVIEW");
    await client.admin.approveArticle(id);
    expect((await client.admin.article(id)).state).toBe("APPROVED");
    await client.admin.scheduleArticle(id, new Date(Date.now() + 86_400_000).toISOString());
    expect((await client.admin.article(id)).state).toBe("SCHEDULED");
    await client.admin.publishArticle(id);
    expect((await client.articles.bySlug(article.slugSuggestion)).state).toBe("PUBLISHED");
    await client.admin.unpublishArticle(id);
    await expect(client.articles.bySlug(article.slugSuggestion)).rejects.toMatchObject({ status: 404 });
    await client.admin.restoreArticle(id);
    await client.admin.unpublishArticle(id);
    await client.admin.archiveArticle(id);
    expect((await client.admin.article(id)).state).toBe("ARCHIVED");
  });

  it("does not approve an image-dependent draft or publish an unapproved manual article", async () => {
    const client = createApiClient({ mode: "fake" });
    const draft = (await client.admin.articles("DRAFTING")).items[0];
    await expect(client.admin.approveArticle(draft.id)).rejects.toMatchObject({ status: 409 });
    const { id } = await client.admin.createArticle(article);
    await expect(client.admin.publishArticle(id)).rejects.toMatchObject({ status: 409 });
    await client.admin.rejectArticle(id);
    await client.admin.archiveArticle(id);
    expect((await client.admin.article(id)).state).toBe("ARCHIVED");
  });

  it("withdraws corrections for review, preserves snapshots and canonical publication, and restores without changing dates", async () => {
    const client = createApiClient({ mode: "fake" });
    const input = { ...article, slugSuggestion: "corrected-workflow" };
    const { id } = await client.admin.createArticle(input);
    await client.admin.approveArticle(id);
    await client.admin.publishArticle(id);
    const published = await client.admin.article(id);
    await expect(client.admin.startCorrection(id, published.version, " ")).rejects.toMatchObject({ status: 400 });
    await expect(client.admin.startCorrection(id, published.version - 1, "Correction.")).rejects.toMatchObject({ status: 409 });
    await client.admin.startCorrection(id, published.version, "Correction: clarified the source.");
    await expect(client.articles.bySlug(published.slug)).rejects.toMatchObject({ status: 404 });
    expect((await client.articles.latest()).some((value) => value.id === id)).toBe(false);
    expect((await client.articles.search("Editorial workflow")).items.some((value) => value.articleId === id)).toBe(false);
    const withdrawn = await client.admin.article(id);
    expect(withdrawn).toMatchObject({ state: "AWAITING_REVIEW", approvedAt: null, approvedBy: null, publishedAt: published.publishedAt });
    await expect(client.admin.publishArticle(id)).rejects.toMatchObject({ status: 409 });
    await client.admin.editArticle(id, withdrawn.version, { ...input, headline: "Corrected headline", slugSuggestion: "must-not-replace-canonical" });
    await client.admin.approveArticle(id);
    await client.admin.publishArticle(id);
    const corrected = await client.articles.bySlug(published.slug);
    expect(corrected).toMatchObject({ id, slug: published.slug, publishedAt: published.publishedAt, headline: "Corrected headline", correctionNote: "Correction: clarified the source." });
    const history = await client.admin.revisions(id, 0, 2);
    expect(history.items).toHaveLength(2);
    expect(history.total).toBeGreaterThan(2);
    const comparison = await client.admin.compareRevisions(id, 3, history.items[0].revisionNumber);
    expect(comparison.changedFields).toEqual(expect.arrayContaining(["headline", "correctionNote"]));
    expect(comparison.from.snapshot?.headline).toBe(input.headline);
    expect(comparison.to.snapshot?.headline).toBe("Corrected headline");
    await client.admin.unpublishArticle(id);
    await expect(client.articles.bySlug(published.slug)).rejects.toMatchObject({ status: 404 });
    await client.admin.restoreArticle(id);
    expect(await client.articles.bySlug(published.slug)).toMatchObject({ id, publishedAt: published.publishedAt, correctionNote: corrected.correctionNote });
  });

  it("replays matching mutation keys without duplicating revisions or newly created articles", async () => {
    const client = createApiClient({ mode: "fake" });
    const key = crypto.randomUUID();
    const created = await client.admin.createArticle({ ...article, slugSuggestion: "idempotent-creation" }, key);
    expect(await client.admin.createArticle({ ...article, slugSuggestion: "idempotent-creation" }, key)).toEqual(created);
    const reordered = Object.fromEntries(Object.entries({ ...article, slugSuggestion: "idempotent-creation" }).reverse()) as ArticleCommand;
    expect(await client.admin.createArticle(reordered, key)).toEqual(created);
    expect((await client.admin.revisions(created.id)).total).toBe(1);
    await expect(client.admin.createArticle({ ...article, headline: "Changed payload" }, key)).rejects.toMatchObject({ status: 409 });
    const arrayKey = crypto.randomUUID();
    const ordered = { ...article, slugSuggestion: "idempotent-array-order", tags: ["Workflow", "News"] };
    await client.admin.createArticle(ordered, arrayKey);
    await expect(client.admin.createArticle({ ...ordered, tags: [...ordered.tags].reverse() }, arrayKey)).rejects.toMatchObject({ status: 409 });
  });

  it("reschedules and cancels with optimistic versions and blocks editing until cancellation", async () => {
    const client = createApiClient({ mode: "fake" });
    const input = { ...article, slugSuggestion: "scheduled-cancellation" };
    const { id } = await client.admin.createArticle(input);
    await client.admin.approveArticle(id);
    const { scheduleId } = await client.admin.scheduleArticle(id, "2035-01-01T10:00:00.000Z");
    const scheduledArticle = await client.admin.article(id);
    await expect(client.admin.editArticle(id, scheduledArticle.version, input)).rejects.toMatchObject({ status: 409 });
    const queued = await client.admin.publicationSchedules("scheduled", 0, 20, id);
    expect(queued.items[0]).toMatchObject({ id: scheduleId, articleVersion: scheduledArticle.version });
    const changed = await client.admin.reschedulePublication(scheduleId, queued.items[0].version, "2035-02-01T10:00:00.000Z");
    expect(changed.publishAt).toBe("2035-02-01T10:00:00.000Z");
    await expect(client.admin.cancelPublication(scheduleId, queued.items[0].version)).rejects.toMatchObject({ status: 409 });
    await client.admin.cancelPublication(scheduleId, changed.version);
    expect((await client.admin.publicationSchedules("scheduled", 0, 20, id)).items).toHaveLength(0);
    const editable = await client.admin.article(id);
    expect(editable.state).toBe("APPROVED");
    await client.admin.editArticle(id, editable.version, input);
    expect((await client.admin.article(id)).state).toBe("AWAITING_REVIEW");
  });

  it("rejects stale lifecycle reviews and never reapplies a successful old approval to a newer revision", async () => {
    const client = createApiClient({ mode: "fake" });
    const input = { ...article, slugSuggestion: "optimistic-review" };
    const { id } = await client.admin.createArticle(input);
    const firstReview = await client.admin.article(id);
    await client.admin.editArticle(id, firstReview.version, { ...input, headline: "Another editor's revision" });
    await expect(client.admin.approveArticle(id, undefined, firstReview.version)).rejects.toMatchObject({ status: 409 });
    await expect(client.admin.rejectArticle(id, undefined, firstReview.version)).rejects.toMatchObject({ status: 409 });
    const reviewed = await client.admin.article(id);
    const approvalKey = crypto.randomUUID();
    await client.admin.approveArticle(id, approvalKey, reviewed.version);
    const approved = await client.admin.article(id);
    await client.admin.editArticle(id, approved.version, { ...input, headline: "An even newer revision" });
    await client.admin.approveArticle(id, approvalKey, reviewed.version);
    expect((await client.admin.article(id)).state).toBe("AWAITING_REVIEW");
    for (const operation of ["approveArticle", "publishArticle", "unpublishArticle", "restoreArticle", "unpublishArticle", "archiveArticle"] as const) {
      const current = await client.admin.article(id);
      await expect(client.admin[operation](id, undefined, current.version - 1)).rejects.toMatchObject({ status: 409 });
      await client.admin[operation](id, undefined, current.version);
    }
    expect((await client.admin.article(id)).state).toBe("ARCHIVED");
  });
});
