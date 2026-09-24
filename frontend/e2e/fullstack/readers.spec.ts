import { expect, type Browser, type Page } from "@playwright/test";
import type { ApiArticle, Page as ApiPage, SourcePost, SourceSummary } from "../../lib/api";
import type { AccountExport, AccountProfile, AccountSession } from "../../lib/identity-api";
import type {
  ArticleCommentSettings, CommentSettings, CommunityComment, Discussion, ModerationItem, Privilege
} from "../../lib/moderation-api";
import {
  administrator, clickMutation, deleteTestAccount, findOnPages, frontend, get, mailLink, moderator,
  mutate, observeRequests, registerVerified, rejectedLogin, signIn, test, uniqueAccount, waitForMail
} from "./phase4-helpers";

test("real reader verifies email, manages versioned profile and sessions, resets password and deletes the account", async ({ page, browser }) => {
  const account = uniqueAccount("identity");
  await registerVerified(page, account, true);
  await page.getByRole("link", { name: "Sign in", exact: true }).first().click();
  await signIn(page, account);
  await expect(page.getByRole("heading", { name: "Profile and account settings" })).toBeVisible();
  const original = await get<AccountProfile>(page.request, "/api/v1/auth/me");
  expect(original).toMatchObject({ email: account.email, emailVerified: true, roles: ["READER"], version: expect.any(Number) });

  const displayName = `${account.displayName} updated`;
  await page.getByLabel("Display name", { exact: true }).fill(displayName);
  const save = await clickMutation(page, page.getByRole("button", { name: "Save profile", exact: true }),
    "PATCH", "/api/v1/auth/me", 200);
  expect(save.request().postDataJSON()).toMatchObject({ displayName, expectedVersion: original.version });
  const updated = await get<AccountProfile>(page.request, "/api/v1/auth/me");
  expect(updated.displayName).toBe(displayName);
  expect(updated.version).toBeGreaterThan(original.version);
  await expect(page.getByLabel("Display name", { exact: true })).toHaveValue(displayName);
  const stale = await mutate(page.request, "PATCH", "/api/v1/auth/me", {
    displayName: "A stale update must not win", expectedVersion: original.version
  });
  expect(stale.status()).toBe(409);
  expect((await get<AccountProfile>(page.request, "/api/v1/auth/me")).displayName).toBe(displayName);

  const secondContext = await browser.newContext({ baseURL: frontend });
  try {
    const second = await secondContext.newPage();
    await signIn(second, account);
    await expect(second.getByRole("heading", { name: "Profile and account settings" })).toBeVisible();
    await page.getByRole("button", { name: "Refresh account", exact: true }).click();
    await expect(page.getByRole("button", { name: "Revoke session", exact: true })).toHaveCount(1);
    const sessions = await get<AccountSession[]>(page.request, "/api/v1/auth/sessions");
    expect(sessions).toHaveLength(2);
    expect(sessions.filter((session) => session.current)).toHaveLength(1);
    const other = sessions.find((session) => !session.current)!;
    await clickMutation(page, page.getByRole("button", { name: "Revoke session", exact: true }),
      "DELETE", `/api/v1/auth/sessions/${other.id}`);
    await expect(page.getByRole("button", { name: "Revoke session", exact: true })).toHaveCount(0);
    expect((await second.request.get("/api/v1/auth/me")).status()).toBe(401);
    await second.goto("/profile");
    await expect(second.getByRole("heading", { name: "Welcome back" })).toBeVisible();
    expect((await get<AccountSession[]>(page.request, "/api/v1/auth/sessions"))).toHaveLength(1);

    const downloadPending = page.waitForEvent("download");
    const exportPending = page.waitForResponse((response) => new URL(response.url()).pathname === "/api/v1/auth/me/export");
    await page.getByRole("button", { name: "Download account data", exact: true }).click();
    const download = await downloadPending;
    expect(download.suggestedFilename()).toBe("account-data.json");
    const exportResponse = await exportPending;
    expect(exportResponse.status()).toBe(200);
    expect(exportResponse.headers()["cache-control"]).toContain("no-store");
    const stream = await download.createReadStream();
    expect(stream).not.toBeNull();
    let contents = "";
    for await (const chunk of stream!) contents += chunk.toString();
    const exported = JSON.parse(contents) as AccountExport;
    expect(exported.profile).toMatchObject({ id: original.id, email: account.email, displayName, emailVerified: true });
    expect(exported.externalIdentities).toEqual([]);
    expect(Number.isNaN(Date.parse(exported.generatedAt))).toBe(false);

    await second.getByRole("link", { name: "Forgot your password?", exact: true }).click();
    const resetForm = second.getByRole("region", { name: "Reset your password", exact: true });
    await resetForm.getByLabel("Email address", { exact: true }).fill(account.email);
    await clickMutation(second, resetForm.getByRole("button", { name: "Send reset link", exact: true }),
      "POST", "/api/v1/auth/password-reset/request", 202);
    await expect(resetForm.getByRole("status")).toContainText("reset instructions are on their way");
    const resetMail = await waitForMail(second.request, account.email, /reset.*password/i);
    const resetRequests = observeRequests(second, "/api/v1/auth/password-reset/confirm");
    try {
      await second.goto(mailLink(resetMail, "/password-reset").href);
      await expect(second.getByRole("heading", { name: "Choose a new password", exact: true })).toBeVisible();
      expect(resetRequests.requests).toHaveLength(0);
      expect((await page.request.get("/api/v1/auth/me")).status()).toBe(200);
      const newPassword = `${account.password}-reset`;
      await second.getByLabel("New password", { exact: true }).fill(newPassword);
      await second.getByLabel("Confirm new password", { exact: true }).fill(newPassword);
      await clickMutation(second, second.getByRole("button", { name: "Reset password", exact: true }),
        "POST", "/api/v1/auth/password-reset/confirm");
      await expect(second.getByRole("status").filter({ hasText: "Your password has been reset." })).toBeVisible();
      expect(resetRequests.requests).toHaveLength(1);
      await expect(second).toHaveURL(new URL("/password-reset", frontend).href);
      expect((await page.request.get("/api/v1/auth/me")).status()).toBe(401);
      await rejectedLogin(page, account);
      await signIn(page, { ...account, password: newPassword });
      await expect(page.getByLabel("Display name", { exact: true })).toHaveValue(displayName);
      const beforeDeletion = await get<AccountProfile>(page.request, "/api/v1/auth/me");
      const deleteButton = page.getByRole("button", { name: "Permanently delete account", exact: true });
      await expect(deleteButton).toBeDisabled();
      await page.getByLabel("Type DELETE to confirm account deletion", { exact: true }).fill("DELETE");
      const deletion = await clickMutation(page, deleteButton, "DELETE", "/api/v1/auth/me");
      expect(deletion.request().postDataJSON()).toEqual({ confirmation: "DELETE", expectedVersion: beforeDeletion.version });
      await expect(page).toHaveURL(new URL("/sign-in?deleted=1", frontend).href);
      expect((await page.request.get("/api/v1/auth/me")).status()).toBe(401);
      await rejectedLogin(page, { ...account, password: newPassword });
      await rejectedLogin(second, account);
    } finally {
      resetRequests.stop();
    }
  } finally {
    await secondContext.close();
  }
});

async function openArticle(page: Page, article: ApiArticle) {
  await page.goto("/");
  const link = page.locator(`a[href="/articles/${article.slug}"]`).first();
  await expect(link, "The published article must be discoverable from the publication").toBeVisible();
  await link.click();
  await expect(page.getByRole("heading", { name: article.headline, exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Refresh comments", exact: true })).toBeEnabled();
}

async function reviewComment(page: Page, body: string, reason: string) {
  await page.getByRole("navigation", { name: "Community sections" }).getByRole("button", { name: "Comments", exact: true }).click();
  const queue = page.getByRole("region", { name: "Moderation queue", exact: true });
  await queue.getByLabel("Comment state", { exact: true }).selectOption("");
  await expect(queue.getByRole("button", { name: "Refresh queue", exact: true })).toBeEnabled();
  const row = queue.locator("li").filter({ has: page.getByText(body, { exact: true }) });
  await findOnPages(page, row, "Comment pages");
  await row.getByRole("button", { name: "Review comment", exact: true }).click();
  await recordApproval(page, reason);
}

async function recordApproval(page: Page, reason: string) {
  const review = page.getByRole("region", { name: "Comment review", exact: true });
  await review.getByLabel("Decision", { exact: true }).selectOption("approved");
  await review.getByLabel("Decision reason", { exact: true }).fill(reason);
  const pending = page.waitForResponse((response) =>
    response.request().method() === "POST" && /\/api\/v1\/admin\/comments\/[^/]+\/moderate$/.test(new URL(response.url()).pathname));
  await review.getByRole("button", { name: "Record decision", exact: true }).click();
  const response = await pending;
  expect(response.status()).toBe(204);
  expect(response.request().headers()["idempotency-key"]).toBeTruthy();
  await expect(review).toContainText(reason);
  await expect(review).toContainText("0 open reports");
}

function globalInput(value: CommentSettings, expectedVersion: number) {
  return {
    enabled: value.enabled, requireApproval: value.requireApproval, editingWindowMinutes: value.editingWindowMinutes,
    reviewSpamThreshold: value.reviewSpamThreshold, rejectSpamThreshold: value.rejectSpamThreshold,
    reportEscalationThreshold: value.reportEscalationThreshold, expectedVersion
  };
}

async function createDiscussionArticle(browser: Browser) {
  const context = await browser.newContext({ baseURL: frontend });
  try {
    const page = await context.newPage();
    await signIn(page, administrator, "/admin/editor");
    const sources = await get<SourceSummary[]>(page.request, "/api/v1/admin/source-posts?status=active&limit=100");
    expect(sources.length, "Local acceptance requires an ingested active source").toBeGreaterThan(0);
    const source = await get<SourcePost>(page.request, `/api/v1/admin/source-posts/${sources[0].id}`);
    const headline = `Phase4 discussion ${Date.now()}`;
    const created = await mutate(page.request, "POST", "/api/v1/admin/articles", {
      headline, summary: "Isolated discussion acceptance.", body: "A sourced discussion fixture.",
      editorialContext: null, seoTitle: headline, seoDescription: "Isolated discussion acceptance.",
      slugSuggestion: headline.toLowerCase().replaceAll(" ", "-"), topic: "Ideas", tags: ["acceptance"],
      sources: [{ sourcePostId: source.id, account: source.handle, postId: source.postId,
        url: source.canonicalUrl, publishedAt: source.publishedAt }], commentsEnabled: true
    });
    expect(created.status()).toBe(201);
    const { id } = await created.json() as { id: string };
    const root = `/api/v1/admin/articles/${id}`;
    const draft = await get<ApiArticle>(page.request, root);
    expect((await mutate(page.request, "POST", `${root}/approve?expectedVersion=${draft.version}`)).status()).toBe(202);
    const approved = await get<ApiArticle>(page.request, root);
    expect((await mutate(page.request, "POST", `${root}/publish?expectedVersion=${approved.version}`)).status()).toBe(204);
    return await get<ApiArticle>(page.request, root);
  } finally {
    await context.close();
  }
}

test("real discussion supports editing, moderation, replies, reports, discovered policies and suspension", async ({ page, browser }) => {
  const selected = await createDiscussionArticle(browser);
  const author = uniqueAccount("author");
  const reporter = uniqueAccount("reporter");
  const adminContext = await browser.newContext({ baseURL: frontend });
  const moderatorContext = await browser.newContext({ baseURL: frontend });
  const reporterContext = await browser.newContext({ baseURL: frontend });
  const admin = await adminContext.newPage();
  const mod = await moderatorContext.newPage();
  const other = await reporterContext.newPage();
  let authorCreated = false;
  let reporterCreated = false;
  let originalGlobal: CommentSettings | undefined;
  let originalArticle: ArticleCommentSettings | undefined;
  let globalVersion: number | undefined;
  let articleVersion: number | undefined;
  const commentIds: string[] = [];
  try {
    await signIn(admin, administrator, "/admin");
    await admin.getByRole("navigation", { name: "Staff workspace" }).getByRole("link", { name: "Comments", exact: true }).click();
    await admin.getByRole("button", { name: "Comment policies", exact: true }).click();
    originalGlobal = await get<CommentSettings>(admin.request, "/api/v1/admin/comment-settings");
    await admin.getByLabel("Enable comments globally", { exact: true }).check();
    await admin.getByLabel("Require approval by default", { exact: true }).check();
    await admin.getByLabel("Own-comment editing window (minutes)", { exact: true }).fill("15");
    await clickMutation(admin, admin.getByRole("button", { name: "Save global comment policy", exact: true }),
      "PUT", "/api/v1/admin/comment-settings");
    globalVersion = (await get<CommentSettings>(admin.request, "/api/v1/admin/comment-settings")).version;
    expect(globalVersion).toBeGreaterThan(originalGlobal.version);
    const policyButton = admin.getByRole("button", { name: `Comment policy for ${selected.headline}`, exact: true });
    await findOnPages(admin, policyButton, "Comment policy article pages");
    await policyButton.click();
    const articlePath = `/api/v1/admin/articles/${selected.id}/comment-settings`;
    originalArticle = await get<ArticleCommentSettings>(admin.request, articlePath);
    const policy = admin.getByRole("region", { name: "Article comment policy", exact: true });
    await policy.getByLabel("Article comment availability", { exact: true }).selectOption("true");
    await policy.getByLabel("Article approval policy", { exact: true }).selectOption("true");
    await clickMutation(admin, policy.getByRole("button", { name: "Save article comment policy", exact: true }), "PUT", articlePath);
    articleVersion = (await get<ArticleCommentSettings>(admin.request, articlePath)).version;
    expect(articleVersion).toBeGreaterThan(originalArticle.version);

    await registerVerified(page, author);
    authorCreated = true;
    await signIn(page, author);
    const authorProfile = await get<AccountProfile>(page.request, "/api/v1/auth/me");
    await registerVerified(other, reporter);
    reporterCreated = true;
    await signIn(other, reporter);
    await openArticle(page, selected);
    const body = `A considered reader response about the evidence from ${author.displayName}.`;
    const edited = `${body} I would welcome more detail on the sources.`;
    await page.getByLabel("Add a comment", { exact: true }).fill(body);
    const submitted = await clickMutation(page, page.getByRole("button", { name: "Submit for moderation", exact: true }),
      "POST", `/api/v1/articles/${selected.id}/comments`, 202);
    expect(submitted.request().headers()["idempotency-key"]).toBeTruthy();
    const rootId = (await submitted.json() as { id: string }).id;
    commentIds.push(rootId);
    const own = page.getByRole("article", { name: "Your comment", exact: true }).filter({ hasText: body });
    await expect(own).toContainText("pending");
    const beforeEdit = (await get<Discussion>(page.request, `/api/v1/articles/${selected.id}/discussion`)).comments.find((value) => value.id === rootId)!;
    await own.getByRole("button", { name: "Edit comment", exact: true }).click();
    await own.getByLabel("Edit your comment", { exact: true }).fill(edited);
    const edit = await clickMutation(page, own.getByRole("button", { name: "Save comment", exact: true }), "PUT", `/api/v1/comments/${rootId}`);
    expect(edit.request().postDataJSON()).toMatchObject({ body: edited, expectedVersion: beforeEdit.version });
    await expect(own).toContainText(edited);
    expect((await mutate(page.request, "PUT", `/api/v1/comments/${rootId}`, { body, expectedVersion: beforeEdit.version })).status()).toBe(409);
    const privateDiscussion = await get<Discussion>(other.request, `/api/v1/articles/${selected.id}/discussion`);
    expect(privateDiscussion.comments.some((value) => value.id === rootId)).toBe(false);

    await signIn(mod, moderator, "/admin");
    await mod.getByRole("navigation", { name: "Staff workspace" }).getByRole("link", { name: "Comments", exact: true }).click();
    await reviewComment(mod, edited, `Reviewed source attribution for ${author.displayName}`);
    expect((await get<ModerationItem>(mod.request, `/api/v1/admin/comments/${rootId}`)).state).toBe("approved");
    await openArticle(other, selected);
    const visible = other.getByRole("article", { name: "Reader comment", exact: true }).filter({ hasText: edited });
    await expect(visible).toBeVisible();
    await visible.getByRole("button", { name: "Reply", exact: true }).click();
    const reply = `Thank you for raising this question, from ${reporter.displayName}.`;
    await visible.getByLabel("Your reply", { exact: true }).fill(reply);
    const replyResponse = await clickMutation(other, visible.getByRole("button", { name: "Submit reply", exact: true }),
      "POST", `/api/v1/articles/${selected.id}/comments`, 202);
    expect(replyResponse.request().postDataJSON()).toMatchObject({ body: reply, parentId: rootId });
    const replyId = (await replyResponse.json() as { id: string }).id;
    commentIds.push(replyId);
    await expect(other.getByRole("article", { name: "Your comment", exact: true }).filter({ hasText: reply })).toBeVisible();
    await visible.getByRole("button", { name: "Report abuse", exact: true }).click();
    const reportDetails = `Please review the context for ${reporter.displayName}.`;
    await visible.getByLabel("Report reason", { exact: true }).selectOption("other");
    await visible.getByLabel("Report details (optional)", { exact: true }).fill(reportDetails);
    await clickMutation(other, visible.getByRole("button", { name: "Send report", exact: true }),
      "POST", `/api/v1/comments/${rootId}/reports`, 202);
    await expect(other.getByRole("status").filter({ hasText: "Report received by the moderation team." })).toBeVisible();
    await mod.getByRole("button", { name: "Abuse reports", exact: true }).click();
    const reported = mod.getByRole("region", { name: "Abuse reports", exact: true }).locator("li").filter({ hasText: reportDetails });
    await reported.getByRole("button", { name: "Inspect reported comment", exact: true }).click();
    await recordApproval(mod, `Report investigated for ${reporter.displayName}`);
    await reviewComment(mod, reply, `Reply reviewed for ${reporter.displayName}`);
    await page.getByRole("button", { name: "Refresh comments", exact: true }).click();
    await expect(page.getByRole("list", { name: "Replies", exact: true }).filter({ hasText: reply })).toBeVisible();

    await admin.getByRole("button", { name: "Commenting privileges", exact: true }).click();
    const directory = admin.getByRole("region", { name: "Community accounts", exact: true });
    await directory.getByLabel("Search accounts", { exact: true }).fill(author.email);
    await directory.getByRole("button", { name: "Search accounts", exact: true }).click();
    await directory.getByRole("button", { name: `Manage ${author.displayName}`, exact: true }).click();
    const privilege = admin.getByRole("region", { name: "Account commenting privilege", exact: true });
    const privilegePath = `/api/v1/admin/commenting-privileges/${authorProfile.id}`;
    const beforePrivilege = await get<Privilege>(admin.request, privilegePath);
    await privilege.getByLabel("Privilege change reason", { exact: true }).fill(`Participation review for ${author.displayName}`);
    const suspension = await clickMutation(admin, privilege.getByRole("button", { name: "Save commenting privilege", exact: true }),
      "POST", `${privilegePath}/suspend`);
    expect(suspension.request().postDataJSON()).toMatchObject({ expectedVersion: beforePrivilege.version, until: null });
    await expect(privilege).toContainText("Commenting: suspended");
    await page.getByRole("button", { name: "Refresh comments", exact: true }).click();
    await expect(page.getByText("Your commenting privilege is suspended indefinitely. You may still delete your own comments.", { exact: true })).toBeVisible();
    await expect(page.getByLabel("Add a comment", { exact: true })).toHaveCount(0);
    const suspendedSubmission = await mutate(page.request, "POST", `/api/v1/articles/${selected.id}/comments`, {
      body: "Suspended accounts must not bypass the browser controls.", parentId: null
    });
    expect(suspendedSubmission.status()).toBe(409);
    expect((await suspendedSubmission.json()).detail).toBe("Commenting privilege is suspended");
    await privilege.getByLabel("Privilege action", { exact: true }).selectOption("restore");
    const restorationReason = `Participation restored for ${author.displayName}`;
    await privilege.getByLabel("Privilege change reason", { exact: true }).fill(restorationReason);
    await clickMutation(admin, privilege.getByRole("button", { name: "Save commenting privilege", exact: true }),
      "POST", `${privilegePath}/restore`);
    await expect(privilege).toContainText("Commenting: allowed");
    await expect(privilege.locator("li").filter({ hasText: restorationReason })).toContainText("restored");
    await page.getByRole("button", { name: "Refresh comments", exact: true }).click();
    await expect(page.getByLabel("Add a comment", { exact: true })).toBeVisible();

    await admin.getByRole("button", { name: "Comment policies", exact: true }).click();
    await findOnPages(admin, policyButton, "Comment policy article pages");
    await policyButton.click();
    await policy.getByLabel("Article comment availability", { exact: true }).selectOption("false");
    await clickMutation(admin, policy.getByRole("button", { name: "Save article comment policy", exact: true }), "PUT", articlePath);
    articleVersion = (await get<ArticleCommentSettings>(admin.request, articlePath)).version;
    await page.getByRole("button", { name: "Refresh comments", exact: true }).click();
    await expect(page.getByText("Comments are disabled for this article.", { exact: true })).toBeVisible();
    await policy.getByLabel("Article comment availability", { exact: true }).selectOption("true");
    await clickMutation(admin, policy.getByRole("button", { name: "Save article comment policy", exact: true }), "PUT", articlePath);
    articleVersion = (await get<ArticleCommentSettings>(admin.request, articlePath)).version;
    await page.getByRole("button", { name: "Refresh comments", exact: true }).click();
    await own.getByRole("button", { name: "Delete comment", exact: true }).click();
    await clickMutation(page, own.getByRole("group", { name: "Confirm comment deletion" }).getByRole("button", { name: "Confirm delete", exact: true }),
      "DELETE", `/api/v1/comments/${rootId}`);
    const deleted = (await get<Discussion>(page.request, `/api/v1/articles/${selected.id}/discussion`)).comments;
    expect(deleted.find((value) => value.id === rootId)).toMatchObject({ deleted: true, deletedByAuthor: true });
    expect(deleted.find((value) => value.id === replyId)).toMatchObject({ parentId: rootId, state: "approved" });
    await other.getByRole("button", { name: "Refresh comments", exact: true }).click();
    await expect(other.getByRole("list", { name: "Replies", exact: true }).filter({ hasText: reply })).toBeVisible();
    const audit = await get<ApiPage<ModerationItem>>(mod.request, `/api/v1/admin/comments?articleId=${selected.id}&size=100`);
    expect(audit.items.find((value) => value.id === rootId)?.deleted).toBe(true);
  } finally {
    try {
      for (const id of commentIds.reverse()) {
        const comment = await get<CommunityComment>(admin.request, `/api/v1/admin/comments/${id}`);
        if (!comment.deleted) {
          expect((await mutate(admin.request, "POST", `/api/v1/admin/comments/${id}/moderate`, {
            decision: "deleted", reason: "Remove this run's Phase4 test contribution", expectedVersion: comment.version
          })).status()).toBe(204);
        }
      }
      if (originalArticle && articleVersion !== undefined) {
        expect((await mutate(admin.request, "PUT", `/api/v1/admin/articles/${selected.id}/comment-settings`, {
          enabledOverride: originalArticle.enabledOverride, requireApprovalOverride: originalArticle.requireApprovalOverride,
          expectedVersion: articleVersion
        })).status()).toBe(204);
      }
      if (originalGlobal && globalVersion !== undefined) {
        expect((await mutate(admin.request, "PUT", "/api/v1/admin/comment-settings", globalInput(originalGlobal, globalVersion))).status()).toBe(204);
      }
      const fixture = await get<ApiArticle>(admin.request, `/api/v1/admin/articles/${selected.id}`);
      expect(fixture.headline).toMatch(/^Phase4 discussion /);
      expect((await mutate(admin.request, "POST",
        `/api/v1/admin/articles/${selected.id}/unpublish?expectedVersion=${fixture.version}`)).status()).toBe(204);
      if (authorCreated) await deleteTestAccount(page.request, author);
      if (reporterCreated) await deleteTestAccount(other.request, reporter);
    } finally {
      await Promise.all([adminContext.close(), moderatorContext.close(), reporterContext.close()]);
    }
  }
});
