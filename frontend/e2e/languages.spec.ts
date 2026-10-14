import { expect, test } from "@playwright/test";

// These tests use unprefixed addresses on purpose: they cover the French default itself.

test("the site opens in French and keeps the reader in their language", async ({ page }) => {
  await page.goto("/");
  await expect(page.locator("html")).toHaveAttribute("lang", "fr");
  const navigation = page.getByRole("navigation", { name: "Navigation principale" });
  await expect(navigation.getByRole("link", { name: "À la une" })).toHaveAttribute("href", "/latest");
  await expect(page.locator('link[rel="alternate"][hreflang="en"]')).toHaveAttribute("href", /\/en$/);

  await navigation.getByRole("link", { name: "Rubriques" }).click();
  await expect(page).toHaveURL(/\/topics$/);
  await expect(page.getByRole("heading", { level: 1, name: "Rubriques" })).toBeVisible();

  await page.getByRole("link", { name: "Read in English" }).click();
  await expect(page).toHaveURL(/\/en\/topics$/);
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await expect(page.getByRole("heading", { level: 1, name: "Topics" })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "Primary navigation" }).getByRole("link", { name: "Latest" })).toHaveAttribute("href", "/en/latest");

  await page.getByRole("link", { name: "Lire en français" }).click();
  await expect(page).toHaveURL(/\/topics$/);
  await expect(page.locator("html")).toHaveAttribute("lang", "fr");
});

test("changing language keeps the search term", async ({ page }) => {
  await page.goto("/search?q=promise");
  await expect(page.getByRole("heading", { level: 1, name: "Recherche" })).toBeVisible();
  await page.getByRole("link", { name: "Read in English" }).click();
  await expect(page).toHaveURL(/\/en\/search\?q=promise$/);
  await expect(page.getByRole("searchbox")).toHaveValue("promise");
});

test("French has one address and English pages keep their protective headers", async ({ request }) => {
  const prefixed = await request.get("/fr/latest", { maxRedirects: 0 });
  expect(prefixed.status()).toBe(308);
  expect(new URL(prefixed.headers().location, "http://placeholder.invalid").pathname).toBe("/latest");

  for (const path of ["/sign-in", "/en/sign-in"]) {
    const headers = (await request.get(path)).headers();
    expect(headers["referrer-policy"], path).toBe("no-referrer");
    expect(headers["cache-control"], path).toContain("no-store");
  }
  expect(await (await request.get("/en/rss.xml")).text()).toContain("<language>en</language>");
  expect(await (await request.get("/rss.xml")).text()).toContain("<language>fr</language>");
});
