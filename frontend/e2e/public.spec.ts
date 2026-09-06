import { expect, test } from "@playwright/test";

test("home page has a readable lead story and navigation", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "For a more attentive public life." })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "Primary navigation" }).getByRole("link", { name: "Latest" })).toBeVisible();
});

test("article detail exposes a canonical story heading", async ({ page }) => {
  await page.goto("/articles/the-work-of-paying-attention");
  await expect(page.getByRole("heading", { name: "The work of paying attention" })).toBeVisible();
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute("href", /articles\/the-work-of-paying-attention$/);
  await expect(page.locator('meta[property="og:type"]')).toHaveAttribute("content", "article");
  const structuredData = JSON.parse(await page.locator('script[type="application/ld+json"]').textContent() || "{}");
  expect(structuredData).toMatchObject({ "@type": "NewsArticle", headline: "The work of paying attention" });
});

test("mobile navigation and comments retain accessible controls", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/articles/the-work-of-paying-attention");
  await expect(page.getByRole("navigation", { name: "Primary navigation" })).toBeVisible();
  await expect(page.getByLabel("Add a comment")).toBeVisible();
  await expect(page.getByRole("button", { name: "Submit for moderation" })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});

test("public discovery, search, and empty states are complete", async ({ page }) => {
  await page.goto("/topics");
  await page.getByRole("link", { name: "Technology", exact: true }).last().click();
  await expect(page).toHaveURL(/\/topics\/technology$/);
  await expect(page.getByRole("heading", { name: "Technology" })).toBeVisible();
  await page.goto("/search?q=promise");
  await expect(page.getByRole("heading", { name: "The tools that keep a promise" })).toBeVisible();
  await page.goto("/search?q=does-not-exist");
  await expect(page.getByText("No published work matches this search yet.")).toBeVisible();
  await page.goto("/articles/does-not-exist");
  await expect(page.getByRole("heading", { name: "Nothing here." })).toBeVisible();
});

test("keyboard and newsletter flows expose accessible feedback", async ({ page }) => {
  await page.goto("/newsletter");
  await page.keyboard.press("Tab");
  await expect(page.getByRole("link", { name: "Skip to content" })).toBeFocused();
  await page.getByLabel("Email address").first().fill("reader@example.com");
  await page.getByRole("button", { name: "Subscribe" }).first().click();
  await expect(page.getByRole("status").filter({ hasText: "Check your inbox" })).toBeVisible();
});

test("metadata feeds and private pages send production-safe responses", async ({ page, request }) => {
  const signIn = await page.goto("/sign-in");
  expect(signIn?.headers()["cache-control"]).toContain("no-store");
  await expect(page).toHaveTitle("Sign in | Nsangusa");
  await expect(page.locator('meta[name="robots"]')).toHaveAttribute("content", /noindex/);

  const admin = await request.get("/admin");
  expect(admin.headers()["cache-control"]).toContain("no-store");
  const rss = await request.get("/rss.xml");
  expect(rss.headers()["content-type"]).toContain("application/rss+xml");
  expect(rss.headers()["cache-control"]).toContain("s-maxage=3600");
  expect(await rss.text()).toContain("<title>The work of paying attention</title>");
});
