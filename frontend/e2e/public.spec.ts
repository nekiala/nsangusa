import { expect, test } from "./english";

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
  await expect(page.getByText("to comment or report abuse.", { exact: false })).toBeVisible();
  await expect(page.getByLabel("Add a comment")).toHaveCount(0);
  await page.goto("/sign-in?next=%2Farticles%2Fthe-work-of-paying-attention");
  await page.getByRole("region", { name: "Welcome back" }).getByLabel("Email address").fill("reader@example.test");
  await page.getByLabel("Password", { exact: true }).fill("reader-demo-password");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
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
  expect(rss.headers()["cache-control"]).toContain("no-store");
  expect(await rss.text()).toContain("<title>The work of paying attention</title>");
  const sitemap = await request.get("/sitemap.xml");
  expect(sitemap.headers()["cache-control"]).toContain("no-store");
  const article = await request.get("/articles/the-work-of-paying-attention");
  expect(article.headers()["cache-control"]).toContain("no-store");
  const missing = await request.get("/articles/does-not-exist");
  expect(missing.status()).toBe(404);
  expect(missing.headers()["cache-control"]).toContain("no-store");
});
