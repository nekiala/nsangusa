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
  await expect(page.getByRole("heading", { name: "Operations summary" })).toBeVisible();
  await page.goto("/profile");
  await expect(page.getByRole("heading", { name: "Your profile & preferences" })).toBeVisible();
  await expect(page.getByLabel("Display name")).toHaveAttribute("readonly", "");
  await expect(page.getByLabel("Newsletter frequency")).toBeDisabled();
});

test("editorial workflow creates, approves, and publishes in fake API mode", async ({ page }) => {
  await signIn(page, "/admin/editor");
  await expect(page.getByRole("heading", { name: "Article editor" })).toBeVisible();

  await page.getByLabel("Headline").fill("A tested editorial workflow");
  await page.getByLabel("Summary").fill("A deterministic article summary.");
  await page.getByLabel("Body").fill("A complete article body for an end-to-end test.");
  await page.getByLabel("SEO title").fill("A tested editorial workflow");
  await page.getByLabel("SEO description").fill("A deterministic article summary.");
  await page.getByLabel("Slug suggestion").fill("a-tested-editorial-workflow");
  await page.getByRole("button", { name: "Create article" }).click();
  const status = page.locator(".admin-content").getByRole("status");
  await expect(status).toContainText("Article created. ID:");

  await page.getByRole("button", { name: "approve" }).click();
  await expect(status).toContainText("Current state: APPROVED");
  await page.getByRole("button", { name: "publish", exact: true }).click();
  await expect(status).toContainText("Current state: PUBLISHED");
});

test("staff navigation remains usable without horizontal overflow on mobile", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await signIn(page);
  await expect(page.getByRole("navigation", { name: "Staff workspace" })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});
