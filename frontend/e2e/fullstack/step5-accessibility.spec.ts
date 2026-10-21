import { randomUUID } from "node:crypto";
import { english, expect } from "../english";
import { type APIRequestContext } from "@playwright/test";
import type { ApiArticle, SourcePost, SourceSummary } from "../../lib/api";
import { contentPlainText, type ArticleContent } from "../../lib/article-content";
import { expectNoHorizontalOverflow, expectReducedMotion, expectVisibleFocus, publicKeyboardJourney, scanAccessibility, tabTo } from "../accessibility-helpers";
import { expectSecurityHeaders, securityHeaderJourney } from "../security-headers-helpers";
import {
  administrator, deleteTestAccount, findOnPages, frontend, get, moderator, mutate,
  registerVerified, signIn, test, uniqueAccount
} from "./phase4-helpers";

const editor = { email: "editor@example.test", password: "editor-demo-password" };

async function createArticle(request: APIRequestContext) {
  const sources = await get<SourceSummary[]>(request, "/api/v1/admin/source-posts?status=active&limit=100");
  expect(sources.length, "The isolated local backend must provide an active ingested source").toBeGreaterThan(0);
  const source = await get<SourcePost>(request, `/api/v1/admin/source-posts/${sources[0].id}`);
  const headline = `Accessibility qualification ${randomUUID().slice(0, 8)}`;
  const content: ArticleContent = { version: 1, blocks: [
    { type: "paragraph", text: "Independent source-backed reading for browser accessibility qualification." },
    { type: "heading", text: "Evidence and context" },
    { type: "quote", text: "A public room offers time to read and reflect." },
    { type: "unordered_list", items: ["Source attribution remains visible.", "Reader contributions remain moderated."] },
    { type: "ordered_list", items: ["Read the evidence.", "Consider the context."] },
    { type: "link", text: "Read the attributed source", url: source.canonicalUrl }
  ] };
  const created = await mutate(request, "POST", "/api/v1/admin/articles", {
    headline, summary: "A self-contained accessibility fixture.", content, body: contentPlainText(content),
    editorialContext: "This article belongs only to this automated qualification scenario.",
    seoTitle: headline, seoDescription: "A self-contained accessibility fixture.",
    slugSuggestion: headline.toLowerCase().replaceAll(" ", "-"), topic: "Ideas", tags: ["qualification"],
    sources: [{ sourcePostId: source.id, account: source.handle, postId: source.postId,
      url: source.canonicalUrl, publishedAt: source.publishedAt }], commentsEnabled: true
  });
  expect(created.status()).toBe(201);
  const { id } = await created.json() as { id: string };
  return get<ApiArticle>(request, `/api/v1/admin/articles/${id}`);
}

async function transition(request: APIRequestContext, id: string, action: "approve" | "publish" | "unpublish" | "reject" | "archive") {
  const root = `/api/v1/admin/articles/${id}`;
  const article = await get<ApiArticle>(request, root);
  expect((await mutate(request, "POST", `${root}/${action}?expectedVersion=${article.version}`)).status()).toBe(action === "approve" ? 202 : 204);
}

test("Step 5 public desktop/mobile WCAG, keyboard, reduced motion and enforced production headers", async ({ page }, info) => {
  await page.goto("/topics");
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute("href", new URL(english("/topics"), frontend).href);
  await securityHeaderJourney(page);
  await publicKeyboardJourney(page, info);
});

test("Step 5 real reader semantics and confirmation focus, moderator review and administrator roles are accessible", async ({ page, browser }, info) => {
  const readerAccount = uniqueAccount("accessibility");
  const adminContext = await browser.newContext({ baseURL: frontend });
  const moderatorContext = await browser.newContext({ baseURL: frontend });
  const admin = await adminContext.newPage();
  const mod = await moderatorContext.newPage();
  let article: ApiArticle | undefined;
  let registered = false;
  try {
    await signIn(admin, administrator, "/admin/editor");
    article = await createArticle(admin.request);
    await transition(admin.request, article.id, "approve");
    await transition(admin.request, article.id, "publish");
    await registerVerified(page, readerAccount);
    registered = true;
    await signIn(page, readerAccount);
    await expect(page.getByLabel("Display name", { exact: true })).toBeVisible();
    await expect(page.getByRole("heading", { name: "This session", exact: true })).toBeVisible();
    await scanAccessibility(page, info, "reader-profile-desktop");
    await page.setViewportSize({ width: 320, height: 740 });
    const deleteAccount = page.getByRole("button", { name: "Permanently delete account", exact: true });
    await expect(deleteAccount).toBeDisabled();
    const accountConfirmation = page.getByLabel("Type DELETE to confirm account deletion", { exact: true });
    await tabTo(page, accountConfirmation);
    await page.keyboard.type("DELETE");
    await page.keyboard.press("Tab");
    await expectVisibleFocus(deleteAccount);
    await page.keyboard.press("Shift+Tab");
    await page.keyboard.press("Backspace");
    await expect(deleteAccount).toBeDisabled();
    await scanAccessibility(page, info, "reader-profile-mobile-320");
    const response = await page.goto(`/articles/${article.slug}`);
    expectSecurityHeaders(response!);
    await expect(page.getByRole("heading", { name: article.headline, exact: true })).toBeVisible();
    const body = page.locator(".article-body");
    await expect(body.getByRole("heading", { name: "Evidence and context", level: 2 })).toBeVisible();
    await expect(body.locator("blockquote")).toHaveText("A public room offers time to read and reflect.");
    await expect(body.locator("ol").getByRole("listitem")).toHaveCount(2);
    await expect(body.getByRole("link", { name: "Read the attributed source", exact: true })).toBeVisible();
    await expect(page.getByRole("region", { name: "Article sources" }).getByRole("link")).toHaveCount(1);
    await expect(page.getByLabel("Add a comment", { exact: true })).toBeVisible();
    await scanAccessibility(page, info, "reader-article-mobile-320");
    const commentBody = `Accessible keyboard response ${randomUUID().slice(0, 8)}`;
    await tabTo(page, page.getByLabel("Add a comment", { exact: true }));
    await page.keyboard.type(commentBody);
    await page.keyboard.press("Tab");
    const submission = page.waitForResponse((result) => result.request().method() === "POST"
      && new URL(result.url()).pathname === `/api/v1/articles/${article!.id}/comments`);
    await page.keyboard.press("Enter");
    expect((await submission).status()).toBe(202);
    const ownComment = page.getByRole("article", { name: "Your comment", exact: true }).filter({ hasText: commentBody });
    await expect(ownComment).toBeVisible();
    const deleteComment = ownComment.getByRole("button", { name: "Delete comment", exact: true });
    await tabTo(page, deleteComment);
    await page.keyboard.press("Enter");
    const cancel = ownComment.getByRole("button", { name: "Cancel deletion", exact: true });
    await expectVisibleFocus(cancel);
    await scanAccessibility(page, info, "reader-inline-confirmation-mobile");
    await page.keyboard.press("Escape");
    await expectVisibleFocus(deleteComment);
    await expect(ownComment.getByRole("group", { name: "Confirm comment deletion" })).toHaveCount(0);
    await page.keyboard.press("Enter");
    await expect(cancel).toBeFocused();
    await page.keyboard.press("Shift+Tab");
    await expectVisibleFocus(ownComment.getByRole("button", { name: "Confirm delete", exact: true }));
    await page.keyboard.press("Tab");
    await page.keyboard.press("Enter");
    await expect(deleteComment).toBeFocused();
    await expectReducedMotion(page);
    await expectNoHorizontalOverflow(page);

    await signIn(mod, moderator, "/admin/comments");
    await mod.getByLabel("Comment state", { exact: true }).selectOption("");
    await expect(mod.getByRole("button", { name: "Refresh queue", exact: true })).toBeEnabled();
    const row = mod.getByRole("region", { name: "Moderation queue" }).locator("li").filter({ hasText: commentBody });
    await findOnPages(mod, row, "Comment pages");
    await tabTo(mod, row.getByRole("button", { name: "Review comment", exact: true }));
    await mod.keyboard.press("Enter");
    const review = mod.getByRole("region", { name: "Comment review", exact: true });
    await expect(review.getByLabel("Decision reason", { exact: true })).toBeVisible();
    await scanAccessibility(mod, info, "moderator-review-desktop");
    await mod.setViewportSize({ width: 390, height: 844 });
    await scanAccessibility(mod, info, "moderator-review-mobile");

    await admin.goto("/admin/users");
    const query = admin.getByLabel("Search users by name or email", { exact: true });
    await tabTo(admin, query);
    await admin.keyboard.type(readerAccount.email);
    await admin.keyboard.press("Enter");
    const manage = admin.getByRole("button", { name: `Manage roles for ${readerAccount.displayName}`, exact: true });
    await expect(manage).toBeVisible();
    await tabTo(admin, manage);
    await admin.keyboard.press("Enter");
    const confirmation = admin.getByLabel(`Type ${readerAccount.email} to confirm`, { exact: true });
    await expect(confirmation).toBeVisible();
    await scanAccessibility(admin, info, "administrator-roles-desktop");
    await admin.setViewportSize({ width: 320, height: 740 });
    await tabTo(admin, confirmation);
    await admin.keyboard.type(readerAccount.email);
    await admin.keyboard.press("Tab");
    await expectVisibleFocus(admin.getByRole("button", { name: "Save roles", exact: true }));
    // Qualify confirmation without changing privileges already covered by Step 4.
    await admin.keyboard.press("Shift+Tab");
    await admin.keyboard.press("Backspace");
    await expect(admin.getByRole("button", { name: "Save roles", exact: true })).toBeDisabled();
    await scanAccessibility(admin, info, "administrator-roles-mobile-320");
  } finally {
    try {
      if (article) {
        const current = await get<ApiArticle>(admin.request, `/api/v1/admin/articles/${article.id}`);
        if (current.state === "PUBLISHED") await transition(admin.request, article.id, "unpublish");
        if (current.state === "AWAITING_REVIEW") await transition(admin.request, article.id, "reject");
        if (["PUBLISHED", "UNPUBLISHED", "AWAITING_REVIEW", "REJECTED"].includes(current.state)) await transition(admin.request, article.id, "archive");
      }
      if (registered) await deleteTestAccount(page.request, readerAccount);
    } finally {
      await adminContext.close();
      await moderatorContext.close();
    }
  }
});

test("Step 5 editor structured-content keyboard preview and responsive workspace have no serious WCAG findings", async ({ page }, info) => {
  await signIn(page, editor, "/admin/editor");
  const article = await createArticle(page.request);
  try {
    const response = await page.goto(`/admin/editor/${article.id}`);
    expectSecurityHeaders(response!);
    await expect(page.getByLabel("Headline", { exact: true })).toHaveValue(article.headline);
    await expect(page.getByRole("button", { name: "Save article", exact: true })).toBeEnabled();
    await expect(page.getByRole("button", { name: "Refresh image review", exact: true })).toBeEnabled();
    await expect(page.getByText("Loading revisions…", { exact: true })).toHaveCount(0);
    await scanAccessibility(page, info, "editor-desktop");
    const headline = page.getByLabel("Headline", { exact: true });
    await tabTo(page, headline);
    await page.keyboard.press("ArrowRight");
    await page.keyboard.type(" keyboard preview");
    await expect(headline).toHaveValue(`${article.headline} keyboard preview`);
    const preview = page.getByRole("button", { name: "Preview article", exact: true });
    await tabTo(page, preview);
    await page.keyboard.press("Enter");
    await expect(page.getByRole("region", { name: "Article preview" })).toContainText(`${article.headline} keyboard preview`);
    await page.setViewportSize({ width: 320, height: 740 });
    await expectReducedMotion(page);
    await scanAccessibility(page, info, "editor-preview-mobile-320");
    expect((await get<ApiArticle>(page.request, `/api/v1/admin/articles/${article.id}`)).headline).toBe(article.headline);
  } finally {
    await transition(page.request, article.id, "reject");
    await transition(page.request, article.id, "archive");
  }
});
