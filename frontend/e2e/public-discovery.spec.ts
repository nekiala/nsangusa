import { expect, test } from "@playwright/test";

test("latest pagination keeps page size, canonical metadata and disjoint results", async ({ page }) => {
  await page.goto("/latest?size=2");
  await expect(page).toHaveTitle("Latest | Nsangusa");
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute("href", /\/latest\?size=2$/);
  const first = await page.locator(".article-card h2").allTextContents();
  await page.getByRole("link", { name: "Next page" }).click();
  await expect(page).toHaveURL(/\/latest\?page=2&size=2$/);
  await expect(page.getByText("Page 2 of 2")).toBeVisible();
  const second = await page.locator(".article-card h2").allTextContents();
  expect(first).toHaveLength(2);
  expect(second).toHaveLength(2);
  expect(second.some((title) => first.includes(title))).toBe(false);
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute("href", /\/latest\?page=2&size=2$/);
  await expect(page).toHaveTitle("Latest — page 2 | Nsangusa");
  await expect(page.getByRole("link", { name: "Next page" })).toHaveCount(0);
  await page.getByRole("link", { name: "Previous page" }).click();
  await expect(page).toHaveURL(/\/latest\?size=2$/);
  await expect(page.getByText("Page 1 of 2")).toBeVisible();
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute("href", /\/latest\?size=2$/);
  await expect(page).toHaveTitle("Latest | Nsangusa");
  await page.goBack();
  await expect(page.getByText("Page 2 of 2")).toBeVisible();
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute("href", /\/latest\?page=2&size=2$/);
  await expect(page).toHaveTitle("Latest — page 2 | Nsangusa");
});

test("search links preserve the query and archive inventories encode facets", async ({ page }) => {
  await page.goto("/search?q=the&size=1");
  await page.getByRole("link", { name: "Next page" }).click();
  await expect(page).toHaveURL(/\/search\?q=the&page=2&size=1$/);
  await expect(page.getByRole("searchbox")).toHaveValue("the");
  await expect(page.locator('meta[name="robots"]')).toHaveAttribute("content", /noindex/);
  await page.goto("/tags");
  await page.getByRole("link", { name: "Public life", exact: true }).click();
  await expect(page).toHaveURL(/\/tags\/public%20life$/);
  await expect(page).toHaveTitle("Tag: Public life | Nsangusa");
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute("href", /\/tags\/public%20life$/);
  await expect(page.getByRole("heading", { name: "The work of paying attention" })).toBeVisible();
});

test("unknown facets and exhausted or invalid pages return real not-found", async ({ page, request }) => {
  for (const path of ["/topics/does-not-exist", "/tags/does-not-exist", "/latest?page=999", "/latest?page=-1"]) {
    const response = await request.get(path);
    expect(response.status(), path).toBe(404);
  }
  await page.goto("/search?q=does-not-exist");
  await expect(page.getByText("No published work matches this search yet.")).toBeVisible();
});

test("the dynamic sitemap index exposes bounded no-store article segments", async ({ request }) => {
  const index = await request.get("/sitemap.xml");
  expect(index.status()).toBe(200);
  expect(index.headers()["cache-control"]).toContain("no-store");
  expect(await index.text()).toContain("<sitemapindex");
  expect(await index.text()).toContain("/sitemaps/articles/0.xml");
  const segment = await request.get("/sitemaps/articles/0.xml");
  expect(segment.headers()["cache-control"]).toContain("no-store");
  expect(await segment.text()).toContain("/articles/the-work-of-paying-attention");
  expect((await request.get("/sitemaps/articles/99.xml")).status()).toBe(404);
  const rss = await request.get("/rss.xml");
  expect(await rss.text()).toContain('rel="self"');
  expect((await request.get("/rss.xml?page=2")).status()).toBe(404);
});
