import { expect, test } from "@playwright/test";

async function signIn(page: import("@playwright/test").Page, destination = "/admin") {
  await page.goto(destination);
  await expect(page).toHaveURL(new RegExp(`/sign-in\\?next=${encodeURIComponent(destination)}$`));
  const form = page.getByRole("region", { name: "Welcome back" });
  await form.getByLabel("Email address").fill("editor@example.com");
  await form.getByLabel("Password").fill("a-secure-password");
  await form.getByRole("button", { name: "Sign in" }).click();
}

test("protected staff and profile routes require and retain authentication", async ({ page }) => {
  await signIn(page);
  await expect(page).toHaveURL(/\/admin$/);
  await expect(page.getByRole("heading", { name: "Editorial dashboard" })).toBeVisible();
  await page.goto("/profile");
  await expect(page.getByRole("heading", { name: "Profile and account settings" })).toBeVisible();
  await expect(page.getByLabel("Display name")).toBeEditable();
  await expect(page.getByLabel("Newsletter frequency")).toBeEnabled();
});

test("editorial workflow publishes, corrects, compares, restores and archives in fake API mode", async ({ page }) => {
  await signIn(page, "/admin/editor/new");
  await expect(page.getByRole("heading", { name: "Article editor" })).toBeVisible();

  await page.getByLabel("Headline").fill("A tested editorial workflow");
  await page.getByLabel("Summary").fill("A deterministic article summary.");
  await page.getByLabel("Body").fill("A complete article body for an end-to-end test.");
  await page.getByLabel("SEO title").fill("A tested editorial workflow");
  await page.getByLabel("SEO description").fill("A deterministic article summary.");
  await page.getByLabel("Slug suggestion").fill("a-tested-editorial-workflow");
  await page.getByLabel("Available source").selectOption({ index: 1 });
  await page.getByRole("button", { name: "Add selected source" }).click();
  await expect(page.getByText("Source added.")).toBeVisible();
  await page.getByRole("button", { name: "Create article" }).click();
  const state = page.getByTestId("article-state");
  await expect(state).toContainText("AWAITING_REVIEW");

  await page.getByRole("button", { name: "Approve article" }).click();
  await expect(state).toContainText("APPROVED");
  await page.getByRole("button", { name: "Publish now", exact: true }).click();
  await expect(state).toContainText("PUBLISHED");
  await expect(page.getByLabel("Headline", { exact: true })).toBeDisabled();
  const correction = page.getByRole("button", { name: "Withdraw and start correction" });
  await expect(correction).toBeDisabled();
  await page.getByLabel("Public correction note (required)").fill("Correction: the source attribution has been clarified.");
  await page.getByLabel("I understand the article will be withdrawn until reviewed and republished.").check();
  await correction.click();
  await expect(state).toContainText("AWAITING_REVIEW");
  await page.getByLabel("Headline", { exact: true }).fill("A corrected editorial workflow");
  await page.getByRole("button", { name: "Save article", exact: true }).click();
  await expect(page.getByRole("button", { name: "Approve article" })).toBeEnabled();
  await page.getByRole("button", { name: "Approve article" }).click();
  await expect(state).toContainText("APPROVED");
  await page.getByRole("button", { name: "Publish now", exact: true }).click();
  await expect(state).toContainText("PUBLISHED");
  await page.getByRole("button", { name: "Compare from revision 3", exact: true }).click();
  await page.getByRole("button", { name: "Compare to revision 7", exact: true }).click();
  await page.getByRole("button", { name: "Compare revisions", exact: true }).click();
  const comparison = page.getByRole("region", { name: "Revision comparison" });
  await expect(comparison).toContainText("A tested editorial workflow");
  await expect(comparison).toContainText("A corrected editorial workflow");
  await page.getByRole("button", { name: "Unpublish article", exact: true }).click();
  await expect(state).toContainText("UNPUBLISHED");
  await page.getByRole("button", { name: "Restore publication", exact: true }).click();
  await expect(state).toContainText("PUBLISHED");
  await page.getByRole("button", { name: "Unpublish article", exact: true }).click();
  await expect(state).toContainText("UNPUBLISHED");
  await page.getByRole("button", { name: "Archive article", exact: true }).click();
  await expect(state).toContainText("ARCHIVED");
});

test("candidate review exposes provenance and explicit image approval", async ({ page }) => {
  await signIn(page, "/admin/candidates");
  await page.getByRole("button", { name: "Review candidate" }).first().click();
  await expect(page.getByRole("heading", { name: "Analysis and provenance" })).toBeVisible();
  await expect(page.getByText("analysis-v1", { exact: true })).toBeVisible();
  await page.getByRole("link", { name: "Open draft article" }).first().click();
  await expect(page.getByRole("button", { name: "Approve article" })).toBeDisabled();
  await expect(page.getByRole("img", { name: "Illustration of a library reading room" })).toBeVisible();
  await page.getByRole("button", { name: "Approve this image" }).click();
  await expect(page.getByTestId("article-state")).toContainText("AWAITING_REVIEW");
  await expect(page.getByRole("button", { name: "Approve article" })).toBeEnabled();
});

test("scheduler queue reschedules and cancels before allowing article edits", async ({ page }) => {
  await signIn(page, "/admin/editor/new");
  for (const [label, value] of [["Headline", "A scheduled test article"], ["Summary", "A scheduled summary."], ["Body", "A complete scheduled article."], ["SEO title", "Scheduled SEO"], ["SEO description", "Scheduled search description."], ["Slug suggestion", "scheduled-test-article"]]) {
    await page.getByLabel(label, { exact: true }).fill(value);
  }
  await page.getByLabel("Available source").selectOption({ index: 1 });
  await page.getByRole("button", { name: "Add selected source" }).click();
  await page.getByRole("button", { name: "Create article", exact: true }).click();
  await expect(page.getByTestId("article-state")).toContainText("AWAITING_REVIEW");
  await page.getByRole("button", { name: "Approve article" }).click();
  await expect(page.getByTestId("article-state")).toContainText("APPROVED");
  await page.getByLabel("Publication date and time (your local time)").fill("2035-01-01T10:00");
  await page.getByRole("button", { name: "Schedule publication" }).click();
  await expect(page.getByTestId("article-state")).toContainText("SCHEDULED");
  await expect(page.getByLabel("Headline", { exact: true })).toBeDisabled();
  await page.getByRole("link", { name: "Manage publication schedules" }).click();
  await expect(page.getByRole("region", { name: "Current publication policy" })).toContainText("HUMAN_REVIEW_ALWAYS");
  await page.getByLabel("New publication time (your local time)").fill("2035-02-15T11:30");
  await page.getByRole("button", { name: "Reschedule publication" }).click();
  await expect(page.getByText(/Schedule version 1/)).toBeVisible();
  await expect(page.getByLabel("New publication time (your local time)")).toHaveValue("2035-02-15T11:30");
  await page.getByRole("button", { name: "Cancel publication schedule" }).click();
  await expect(page.getByText("No publication schedules in this queue.")).toBeVisible();
  await page.getByLabel("Schedule status").selectOption("cancelled");
  await expect(page.getByText("cancelled", { exact: true }).last()).toBeVisible();
  await expect(page.getByRole("button", { name: "Cancel publication schedule" })).toHaveCount(0);
  await page.locator(".queue-list").getByRole("link").click();
  await expect(page.getByTestId("article-state")).toContainText("APPROVED");
  await expect(page.getByLabel("Headline", { exact: true })).toBeEnabled();
});

test("staff navigation remains usable without horizontal overflow on mobile", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await signIn(page);
  await expect(page.getByRole("navigation", { name: "Staff workspace" })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});
