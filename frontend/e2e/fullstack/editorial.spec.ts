import { expect, type APIRequestContext, type Page as BrowserPage } from "@playwright/test";
import type { ApiArticle, ImageGeneration, Page, PublicationSchedule, RevisionView, StoryCandidate } from "../../lib/api";
import { clickMutation, mutate, test } from "./phase4-helpers";

type MailSummary = { ID: string; Subject: string };
type MailMessage = { ID: string; Subject: string; Text: string; HTML: string };
const frontend = process.env.FULLSTACK_FRONTEND_URL || "http://127.0.0.1:13000";
const mailpit = process.env.FULLSTACK_MAILPIT_URL || "http://127.0.0.1:8025";

async function get<T>(request: APIRequestContext, path: string): Promise<T> {
  const response = await request.get(path);
  expect(response.ok(), `${path}: ${response.status()}`).toBeTruthy();
  return response.json() as Promise<T>;
}

async function waitForMail(request: APIRequestContext, recipient: string, subject: RegExp): Promise<MailMessage> {
  let messageId = "";
  await expect.poll(async () => {
    const result = await get<{ messages: MailSummary[] }>(request, `${mailpit}/api/v1/search?query=${encodeURIComponent(`to:${recipient}`)}`);
    messageId = result.messages.find((message) => subject.test(message.Subject))?.ID || "";
    return messageId;
  }, { timeout: 60_000, intervals: [500, 1000, 2000] }).not.toBe("");
  return get<MailMessage>(request, `${mailpit}/api/v1/message/${messageId}`);
}

function newsletterLink(message: MailMessage, action: "confirm" | "unsubscribe") {
  const text = `${message.Text}\n${message.HTML}`.replaceAll("&amp;", "&");
  const match = text.match(new RegExp(`https?://[^\\s"'<>]+/newsletter/${action}\\?[^\\s"'<>]+`));
  expect(match, `The email must link to the browser ${action} page`).not.toBeNull();
  const url = new URL(match![0]);
  expect(url.origin).toBe(new URL(frontend).origin);
  return url.href;
}

async function currentArticle(page: BrowserPage, id: string) {
  return get<ApiArticle>(page.request, `/api/v1/admin/articles/${id}`);
}

async function refreshAfterImageApproval(page: BrowserPage, id: string) {
  const selected = (await currentArticle(page, id)).approvedImageGenerationId;
  await expect.poll(async () => {
    const revisions = await get<Page<RevisionView>>(page.request, `/api/v1/admin/articles/${id}/revisions?page=0&size=20`);
    return revisions.items.some((revision) => revision.reason === "IMAGE_APPROVED"
      && revision.snapshot?.approvedImageGenerationId === selected);
  }).toBe(true);
  const refreshed = page.waitForResponse((response) => response.request().method() === "GET"
    && new URL(response.url()).pathname === `/api/v1/admin/articles/${id}`);
  await page.getByRole("button", { name: "Refresh article", exact: true }).click();
  const article = await (await refreshed).json() as ApiArticle;
  await expect(page.getByTestId("article-state")).toContainText(`Version ${article.version}`);
  return article;
}

test("real editorial pipeline, corrections, revision history, schedule management and newsletter consent", async ({ page, context }, testInfo) => {
  const email = process.env.FULLSTACK_EDITOR_EMAIL;
  const password = process.env.FULLSTACK_EDITOR_PASSWORD;
  expect(email, "Set FULLSTACK_EDITOR_EMAIL to an isolated administrator account").toBeTruthy();
  expect(password, "Set FULLSTACK_EDITOR_PASSWORD to that account's password").toBeTruthy();
  const stamp = Date.now().toString();
  const handle = `e2e${stamp.slice(-10)}`;
  const headline = `Fullstack library report ${stamp}`;
  const topic = `e2e-${stamp}`;
  const subscriber = `step2-${stamp}@example.test`;
  const reader = await context.newPage();

  await page.goto("/admin/handles");
  await page.getByRole("region", { name: "Welcome back" }).getByLabel("Email address").fill(email!);
  await page.getByLabel("Password").fill(password!);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "X accounts", exact: true })).toBeVisible();
  const capabilities = await get<{ simulationEnabled: boolean }>(page.request, "/api/v1/admin/x-accounts/capabilities");
  expect(capabilities.simulationEnabled, "Run this suite against the backend's local fake providers").toBe(true);

  await page.getByRole("button", { name: "Add an account" }).click();
  await page.getByLabel("Handle", { exact: true }).fill(handle);
  await page.getByRole("button", { name: "Resolve handle" }).click();
  await expect(page.getByLabel("Official X account ID")).toHaveValue(/^\d+$/);
  await page.getByLabel("Display name").fill(`Fullstack fixture ${stamp}`);
  await page.getByLabel("Topics, separated by commas").fill(topic);
  const addedAccount = page.waitForResponse((response) => response.request().method() === "POST" && new URL(response.url()).pathname === "/api/v1/admin/x-accounts");
  await page.getByRole("button", { name: "Add account", exact: true }).click();
  const account = await (await addedAccount).json() as { id: string };
  await expect(page.getByLabel("Monitored account")).toHaveValue(account.id);

  const sourceIds: string[] = [];
  for (const suffix of ["01", "02"]) {
    await page.getByLabel("Post ID", { exact: true }).fill(`${stamp}${suffix}`);
    const canonicalUrl = `https://x.com/${handle}/status/${stamp}${suffix}`;
    await page.getByLabel("Canonical URL").fill(`${canonicalUrl}?s=20&t=shared#replies`);
    await page.getByLabel("Permitted text").fill(`Library programme ${stamp}: council opens evening library reading rooms for residents. Source report ${suffix}. #${topic}`);
    await page.getByLabel("Published at", { exact: true }).fill(new Date().toISOString().slice(0, 16));
    const postResponse = page.waitForResponse((response) => response.request().method() === "POST" && response.url().includes("/simulate-post"));
    await page.getByRole("button", { name: "Simulate post" }).click();
    const response = await postResponse;
    expect(response.ok()).toBeTruthy();
    const sourceId = (await response.json() as { id: string }).id;
    sourceIds.push(sourceId);
    expect((await get<{ canonicalUrl: string }>(page.request, `/api/v1/admin/source-posts/${sourceId}`)).canonicalUrl).toBe(canonicalUrl);
    await expect(page.getByRole("button", { name: "Simulate post" })).toBeEnabled();
  }
  await page.getByRole("button", { name: "Pause monitoring" }).click();
  await expect(page.getByRole("button", { name: "Resume monitoring" })).toBeVisible();

  let candidate: StoryCandidate | undefined;
  await expect.poll(async () => {
    const result = await get<Page<StoryCandidate>>(page.request, "/api/v1/admin/story-candidates?page=0&size=100");
    candidate = result.items.find((item) => sourceIds.every((id) => item.sourceIds.includes(id)));
    return candidate?.articleId || "";
  }, { timeout: 120_000, intervals: [1000, 2000] }).not.toBe("");
  const candidateId = candidate!.id;
  const articleId = candidate!.articleId!;
  await page.goto("/admin/candidates");
  const candidateRow = page.locator(".queue-list > li").filter({ hasText: candidateId });
  await candidateRow.getByRole("button", { name: "Review candidate", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Analysis and provenance" })).toBeVisible();
  await expect(page.getByText("Claims and supporting evidence", { exact: true }).first()).toBeVisible();
  const requests = await get<{ provider: string }[]>(page.request, `/api/v1/admin/ai-requests?storyCandidateId=${candidateId}`);
  expect(requests.length).toBeGreaterThan(0);
  expect(requests.every((request) => request.provider === "fake")).toBeTruthy();
  await candidateRow.getByRole("link", { name: "Open draft article" }).click();
  await expect(page.getByRole("button", { name: "Approve article" })).toBeDisabled();

  await expect.poll(async () => (await get<ImageGeneration[]>(page.request, `/api/v1/admin/articles/${articleId}/images`)).length, { timeout: 60_000 }).toBeGreaterThan(0);
  await page.getByRole("button", { name: "Refresh image review" }).click();
  await expect(page.getByRole("img").first()).toBeVisible();
  await expect.poll(() => page.getByRole("img").first().evaluate((image: HTMLImageElement) => image.naturalWidth)).toBeGreaterThan(0);
  await page.getByRole("button", { name: "Approve this image" }).first().click();
  await expect.poll(async () => (await currentArticle(page, articleId)).imageApprovalRequired).toBe(false);
  await page.getByRole("button", { name: "Refresh article", exact: true }).click();
  const originalImage = (await currentArticle(page, articleId)).approvedImageGenerationId;
  expect(originalImage).toBeTruthy();

  await page.getByLabel("Image prompt").fill(`Symbolic evening library illustration ${stamp}, no documentary photography.`);
  await page.getByLabel("Image alternative text").fill(`Illustrated library ${stamp}`);
  await page.getByRole("button", { name: "Request image generation" }).click();
  await expect.poll(async () => (await get<ImageGeneration[]>(page.request, `/api/v1/admin/articles/${articleId}/images`)).length, { timeout: 60_000 }).toBeGreaterThan(1);
  expect((await currentArticle(page, articleId)).approvedImageGenerationId).toBe(originalImage);
  await page.getByRole("button", { name: "Refresh image review" }).click();
  await page.getByRole("region", { name: "Image review" }).locator("article").filter({ has: page.getByRole("heading", { name: "Image awaiting review", exact: true }) }).first().getByRole("button", { name: "Approve this image" }).click();
  await expect.poll(async () => (await currentArticle(page, articleId)).approvedImageGenerationId).not.toBe(originalImage);
  const beforeFallback = await refreshAfterImageApproval(page, articleId);
  const fallbackPath = `/api/v1/admin/articles/${articleId}/images/fallback`;
  const staleFallback = await mutate(page.request, "POST", fallbackPath, {
    altText: "Stale fallback", reason: "Must not create a candidate", expectedVersion: beforeFallback.version - 1
  });
  expect(staleFallback.status()).toBe(409);
  await page.getByLabel("Fallback alternative text", { exact: true }).fill(`Neutral library illustration ${stamp}`);
  await page.getByLabel("Fallback selection reason", { exact: true }).fill("Use original neutral artwork for this report.");
  const fallbackResponse = await clickMutation(page, page.getByRole("button", { name: "Create fallback for review" }),
    "POST", fallbackPath, 202);
  expect(fallbackResponse.request().postDataJSON().expectedVersion).toBe(beforeFallback.version);
  expect(fallbackResponse.request().headers()["idempotency-key"]).toBeTruthy();
  expect((await currentArticle(page, articleId)).approvedImageGenerationId).toBe(beforeFallback.approvedImageGenerationId);
  const fallbackPanel = page.getByRole("region", { name: "Image review" }).locator("article")
    .filter({ has: page.getByText(/^Original neutral editorial fallback illustration, not AI-generated/) });
  await expect(fallbackPanel.getByRole("img")).toBeVisible();
  await expect.poll(() => fallbackPanel.getByRole("img").evaluate((image: HTMLImageElement) => image.naturalWidth)).toBe(1600);
  await fallbackPanel.getByRole("button", { name: "Approve this image" }).click();
  await expect.poll(async () => (await currentArticle(page, articleId)).generatedImage).toBe(false);
  await refreshAfterImageApproval(page, articleId);
  const initialSources = (await currentArticle(page, articleId)).sources.map((source) => source.sourcePostId);
  expect(initialSources.length).toBeGreaterThanOrEqual(2);
  await expect(page.locator(".source-list > li")).toHaveCount(initialSources.length);
  await page.getByLabel("Headline", { exact: true }).fill(headline);
  await page.getByLabel("Summary", { exact: true }).fill(`A reviewed two-source report ${stamp}.`);
  await page.getByLabel("Body", { exact: true }).fill(`Library evidence ${stamp} is attributed to both sources.\n\n<b>This remains plain text.</b>`);
  let blockNumber = (await currentArticle(page, articleId)).content!.blocks.length;
  for (const [type, label, text] of [
    ["heading", "Heading", `Reviewed evidence ${stamp}`],
    ["quote", "Quote", "Attributed words remain a quotation."],
    ["unordered_list", "List item 1 in block", "First supporting point"],
    ["ordered_list", "List item 1 in block", "First reported step"],
    ["link", "Link", "Read the source report"]
  ]) {
    await page.getByLabel("New block type", { exact: true }).selectOption(type);
    await page.getByRole("button", { name: "Add content block", exact: true }).click();
    blockNumber++;
    await page.getByLabel(type.endsWith("_list") ? `${label} ${blockNumber}` : `${label} text ${blockNumber}`, { exact: true }).fill(text);
    if (type === "link") await page.getByLabel(`Link URL ${blockNumber}`, { exact: true }).fill("https://example.test/library-report");
  }
  await page.getByLabel("SEO title").fill(headline);
  await page.getByLabel("SEO description").fill(`Reviewed library evidence ${stamp}.`);
  await page.getByLabel("Editorial context", { exact: true }).fill(`Editorial context for ${stamp}.`);
  await page.getByRole("button", { name: "Preview article" }).click();
  await expect(page.getByRole("region", { name: "Article preview" })).toContainText("<b>This remains plain text.</b>");
  await expect(page.getByRole("region", { name: "Article preview" }).locator("b")).toHaveCount(0);
  await expect(page.getByRole("region", { name: "Article preview" }).getByRole("heading", { name: `Reviewed evidence ${stamp}` })).toBeVisible();
  await page.getByRole("button", { name: "Save article", exact: true }).click();
  await expect(page.getByRole("button", { name: "Approve article" })).toBeEnabled();
  expect((await currentArticle(page, articleId)).sources.map((source) => source.sourcePostId).sort()).toEqual([...initialSources].sort());

  await reader.goto("/newsletter");
  await reader.getByLabel("Email address").first().fill(subscriber);
  await reader.getByLabel("Delivery frequency").first().selectOption("immediate");
  await reader.getByRole("button", { name: "Subscribe", exact: true }).first().click();
  await expect(reader.getByText("Check your inbox to confirm your subscription.")).toBeVisible();
  const confirmation = await waitForMail(page.request, subscriber, /confirm/i);
  let confirmationCalls = 0;
  reader.on("request", (request) => { if (new URL(request.url()).pathname === "/api/v1/newsletter/confirm") confirmationCalls++; });
  const confirmResponse = await reader.goto(newsletterLink(confirmation, "confirm"));
  expect(confirmResponse?.headers()["cache-control"]).toContain("no-store");
  expect(confirmationCalls).toBe(0);
  await reader.getByRole("button", { name: "Confirm subscription" }).click();
  await expect(reader.getByRole("status").filter({ hasText: "Your subscription is confirmed." })).toBeVisible();
  expect(confirmationCalls).toBe(1);

  const reviewRefresh = page.waitForResponse((response) => response.request().method() === "GET" && new URL(response.url()).pathname === `/api/v1/admin/articles/${articleId}`);
  await page.getByRole("button", { name: "Refresh article", exact: true }).click();
  const reviewedVersion = (await (await reviewRefresh).json() as ApiArticle).version;
  await expect(page.getByTestId("article-state")).toContainText(`Version ${reviewedVersion}`);
  const approvalRequest = page.waitForRequest((request) => request.method() === "POST" && new URL(request.url()).pathname === `/api/v1/admin/articles/${articleId}/approve`);
  await page.getByRole("button", { name: "Approve article" }).click();
  expect(new URL((await approvalRequest).url()).searchParams.get("expectedVersion")).toBe(String(reviewedVersion));
  await expect(page.getByTestId("article-state")).toContainText("APPROVED");
  const approvedVersion = (await currentArticle(page, articleId)).version;
  const publicationRequest = page.waitForRequest((request) => request.method() === "POST" && new URL(request.url()).pathname === `/api/v1/admin/articles/${articleId}/publish`);
  await page.getByRole("button", { name: "Publish now" }).click();
  expect(new URL((await publicationRequest).url()).searchParams.get("expectedVersion")).toBe(String(approvedVersion));
  await expect(page.getByTestId("article-state")).toContainText("PUBLISHED");
  const published = await currentArticle(page, articleId);
  const firstPublicResponse = await reader.goto(`/articles/${published.slug}`);
  expect(firstPublicResponse?.headers()["cache-control"]).toContain("no-store");
  await expect(reader.getByRole("heading", { name: headline, exact: true })).toBeVisible();
  await expect(reader.getByText(/<b>This remains plain text.<\/b>/)).toBeVisible();
  await expect(reader.getByRole("heading", { name: `Reviewed evidence ${stamp}`, level: 2 })).toBeVisible();
  await expect(reader.locator("blockquote")).toHaveText("Attributed words remain a quotation.");
  await expect(reader.getByRole("listitem").filter({ hasText: "First supporting point" })).toBeVisible();
  await expect(reader.locator("ol").getByRole("listitem").filter({ hasText: "First reported step" })).toBeVisible();
  await expect(reader.getByRole("link", { name: "Read the source report", exact: true })).toHaveAttribute("href", "https://example.test/library-report");
  await expect(reader.getByRole("region", { name: "Article sources" }).getByRole("link")).toHaveCount(initialSources.length);
  await expect(reader.getByText("AI-generated editorial illustration. This is not a documentary photograph.")).toHaveCount(0);
  await expect(reader.getByRole("img").first()).toHaveAttribute("alt", `Neutral library illustration ${stamp}`);
  await expect.poll(() => reader.getByRole("img").first().evaluate((image: HTMLImageElement) => image.naturalWidth)).toBeGreaterThan(0);
  const publicationMail = await waitForMail(page.request, subscriber, new RegExp(stamp));
  expect(publicationMail.Text).toContain(`/articles/${published.slug}`);
  const beforeCorrection = await get<Page<RevisionView>>(page.request, `/api/v1/admin/articles/${articleId}/revisions?page=0&size=20`);
  const publishedRevision = beforeCorrection.items.find((revision) => revision.snapshot?.state === "PUBLISHED")!;
  expect(publishedRevision).toBeTruthy();
  expect(publishedRevision.snapshot!.content).toEqual(published.content);
  expect(publishedRevision.snapshot!.generatedImage).toBe(false);
  const correctionNote = `Correction ${stamp}: clarified the attribution without changing the article's identity.`;
  await page.getByLabel("Public correction note (required)").fill(correctionNote);
  await page.getByLabel("I understand the article will be withdrawn until reviewed and republished.").check();
  await page.getByRole("button", { name: "Withdraw and start correction" }).click();
  await expect(page.getByTestId("article-state")).toContainText("AWAITING_REVIEW");
  expect((await reader.request.get(`/api/v1/articles/${published.slug}`)).status()).toBe(404);
  expect((await reader.request.get(`/articles/${published.slug}`)).status()).toBe(404);
  await page.getByLabel("Body", { exact: true }).fill(`Library evidence ${stamp} is attributed to both sources.\n\n<b>This remains plain text.</b>\n\nThe source attribution has been clarified.`);
  await page.getByRole("button", { name: "Save article", exact: true }).click();
  await expect(page.getByRole("button", { name: "Approve article" })).toBeEnabled();
  await page.getByRole("button", { name: "Approve article" }).click();
  await expect(page.getByTestId("article-state")).toContainText("APPROVED");
  await page.getByRole("button", { name: "Publish now" }).click();
  await expect(page.getByTestId("article-state")).toContainText("PUBLISHED");
  const corrected = await currentArticle(page, articleId);
  expect(corrected).toMatchObject({ id: articleId, slug: published.slug, publishedAt: published.publishedAt, correctionNote });
  await reader.goto(`/articles/${published.slug}`);
  await expect(reader.getByRole("complementary", { name: "Correction note" })).toContainText(correctionNote);
  const correctedRevisions = await get<Page<RevisionView>>(page.request, `/api/v1/admin/articles/${articleId}/revisions?page=0&size=20`);
  const correctedRevision = correctedRevisions.items.find((revision) => revision.snapshot?.state === "PUBLISHED" && revision.snapshot.correctionNote === correctionNote)!;
  expect(correctedRevision).toBeTruthy();
  await page.getByLabel("From revision number").fill(String(publishedRevision.revisionNumber));
  await page.getByLabel("To revision number").fill(String(correctedRevision.revisionNumber));
  await page.getByRole("button", { name: "Compare revisions", exact: true }).click();
  await expect(page.getByRole("region", { name: "Revision comparison" })).toContainText(correctionNote);
  await page.getByRole("button", { name: "Unpublish article", exact: true }).click();
  await expect(page.getByTestId("article-state")).toContainText("UNPUBLISHED");
  expect((await reader.request.get(`/api/v1/articles/${published.slug}`)).status()).toBe(404);
  expect((await reader.request.get(`/articles/${published.slug}`)).status()).toBe(404);
  for (const path of ["/rss.xml", "/sitemap.xml"]) {
    const response = await reader.request.get(path);
    expect(response.headers()["cache-control"]).toContain("no-store");
    expect(await response.text()).not.toContain(`/articles/${published.slug}`);
  }
  await page.getByRole("button", { name: "Restore publication", exact: true }).click();
  await expect(page.getByTestId("article-state")).toContainText("PUBLISHED");
  expect(await currentArticle(page, articleId)).toMatchObject({ id: articleId, slug: published.slug, publishedAt: published.publishedAt, correctionNote });
  await reader.goto(`/articles/${published.slug}`);
  await expect(reader.getByRole("complementary", { name: "Correction note" })).toContainText(correctionNote);
  await page.waitForTimeout(5000);
  const publicationMessages = await get<{ messages: MailSummary[] }>(page.request, `${mailpit}/api/v1/search?query=${encodeURIComponent(`to:${subscriber}`)}`);
  expect(publicationMessages.messages.filter((message) => message.Subject.includes(stamp))).toHaveLength(1);
  let unsubscribeCalls = 0;
  reader.on("request", (request) => { if (new URL(request.url()).pathname === "/api/v1/newsletter/unsubscribe") unsubscribeCalls++; });
  await reader.goto(newsletterLink(publicationMail, "unsubscribe"));
  expect(unsubscribeCalls).toBe(0);
  await reader.getByRole("button", { name: "Unsubscribe", exact: true }).click();
  await expect(reader.getByRole("status").filter({ hasText: "You have been unsubscribed." })).toBeVisible();
  expect(unsubscribeCalls).toBe(1);

  await page.goto("/admin/editor/new");
  for (const [label, value] of [["Headline", `Scheduled ${stamp}`], ["Summary", "Scheduled fixture."], ["Body", "A sourced scheduled test article."], ["SEO title", `Scheduled ${stamp}`], ["SEO description", "Scheduled fixture."], ["Slug suggestion", `scheduled-${stamp}`]]) {
    await page.getByLabel(label, { exact: true }).fill(value);
  }
  await page.getByLabel("Available source").selectOption(sourceIds[0]);
  await page.getByRole("button", { name: "Add selected source" }).click();
  const createdArticle = page.waitForResponse((response) => response.request().method() === "POST" && new URL(response.url()).pathname === "/api/v1/admin/articles");
  await page.getByRole("button", { name: "Create article", exact: true }).click();
  const createdResponse = await createdArticle;
  expect(createdResponse.status()).toBe(201);
  const scheduledId = (await createdResponse.json() as { id: string }).id;
  await expect(page).toHaveURL(new URL(`/admin/editor/${scheduledId}`, frontend).href);
  await expect(page.getByTestId("article-state")).toContainText("AWAITING_REVIEW");
  await page.getByRole("button", { name: "Approve article" }).click();
  await expect(page.getByTestId("article-state")).toContainText("APPROVED");
  const chosenTime = new Date(Date.now() + 2 * 86_400_000).toISOString().slice(0, 16);
  await page.getByLabel("Publication date and time (your local time)").fill(chosenTime);
  const scheduledRequest = page.waitForRequest((request) => request.method() === "POST" && request.url().includes(`/${scheduledId}/schedule`));
  await page.getByRole("button", { name: "Schedule publication" }).click();
  expect((await scheduledRequest).postDataJSON()).toEqual({ publishAt: `${chosenTime}:00.000Z` });
  await expect(page.getByTestId("article-state")).toContainText("SCHEDULED");
  await expect(page.getByLabel("Headline", { exact: true })).toBeDisabled();
  await page.getByRole("link", { name: "Manage publication schedules" }).click();
  await expect(page.getByRole("region", { name: "Current publication policy" })).toContainText("Read-only backend configuration");
  const schedulePage = await get<Page<PublicationSchedule>>(page.request, `/api/v1/admin/publication-schedules?page=0&size=100&articleId=${scheduledId}`);
  const schedule = schedulePage.items.find((entry) => entry.status === "scheduled")!;
  expect(schedule).toBeTruthy();
  const scheduleRow = page.getByRole("listitem", { name: `Publication schedule ${schedule.id}` });
  const rescheduledTime = new Date(Date.now() + 3 * 86_400_000).toISOString().slice(0, 16);
  await scheduleRow.getByLabel("New publication time (your local time)").fill(rescheduledTime);
  const rescheduling = page.waitForResponse((response) => response.request().method() === "PATCH" && response.url().endsWith(`/publication-schedules/${schedule.id}`));
  await scheduleRow.getByRole("button", { name: "Reschedule publication" }).click();
  const rescheduled = await (await rescheduling).json() as PublicationSchedule;
  expect(new Date(rescheduled.publishAt).toISOString()).toBe(`${rescheduledTime}:00.000Z`);
  expect(rescheduled.version).toBeGreaterThan(schedule.version);
  await expect(scheduleRow).toContainText(`Schedule version ${rescheduled.version}`);
  await scheduleRow.getByRole("button", { name: "Cancel publication schedule" }).click();
  await expect(scheduleRow).not.toBeVisible();
  await page.goto(`/admin/editor/${scheduledId}`);
  await expect(page.getByLabel("Headline", { exact: true })).toBeEnabled();
  expect((await get<Page<PublicationSchedule>>(page.request, `/api/v1/admin/publication-schedules?page=0&size=100&articleId=${scheduledId}`)).items[0].status).toBe("cancelled");

  await page.goto("/admin/candidates");
  const original = page.locator(".queue-list > li").filter({ hasText: candidateId });
  const regeneration = page.waitForResponse((response) => response.request().method() === "POST" && response.url().includes(`/story-candidates/${candidateId}/regenerate`));
  await original.getByRole("button", { name: "Regenerate candidate" }).click();
  const regenerated = await (await regeneration).json() as { id: string };
  expect(regenerated.id).not.toBe(candidateId);
  expect(await currentArticle(page, articleId)).toMatchObject({ id: articleId, headline, state: "PUBLISHED" });
  await testInfo.attach("generated-fixture-identifiers", { body: JSON.stringify({ accountId: account.id, sourceIds, candidateId, articleId, scheduledId, regeneratedCandidateId: regenerated.id }), contentType: "application/json" });
  await reader.close();
});
