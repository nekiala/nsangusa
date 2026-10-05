import { expect, test } from "./english";
import { publicKeyboardJourney, scanAccessibility } from "./accessibility-helpers";

// Fast fake-mode regression coverage complements, but does not replace, the real-backend gate.
test("public accessibility, mobile reflow and keyboard navigation", async ({ page }, info) => {
  await publicKeyboardJourney(page, info);
});

test("protected workspace accessibility regression across roles and responsive layouts", async ({ page }, info) => {
  test.setTimeout(60_000);
  for (const [role, destination] of [["reader", "/profile"], ["moderator", "/admin/comments"],
    ["admin", "/admin/users"], ["editor", "/admin/candidates"]]) {
    await page.goto(`/sign-in?next=${encodeURIComponent(destination)}`);
    const form = page.getByRole("region", { name: "Welcome back", exact: true });
    await form.getByLabel("Email address", { exact: true }).fill(`${role}@example.test`);
    await form.getByLabel("Password", { exact: true }).fill("a-secure-password");
    await form.getByRole("button", { name: "Sign in", exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`${destination}$`));
    if (role === "reader") await expect(page.getByLabel("Display name", { exact: true })).toBeVisible();
    if (role === "moderator") {
      await page.getByRole("button", { name: "Review comment", exact: true }).first().click();
      await expect(page.getByLabel("Decision reason", { exact: true })).toBeVisible();
    }
    if (role === "admin") {
      await page.getByRole("button", { name: "Manage roles for Reader", exact: true }).click();
      await expect(page.getByLabel("Type reader@example.test to confirm", { exact: true })).toBeVisible();
    }
    if (role === "editor") {
      await page.getByRole("link", { name: "Open draft article", exact: true }).first().click();
      await expect(page.getByLabel("Headline", { exact: true })).toBeVisible();
      await expect(page.getByRole("img").first()).toBeVisible();
      expect(await page.getByRole("img").first().getAttribute("src")).toMatch(/^blob:/);
      await expect.poll(() => page.getByRole("img").first().evaluate((image: HTMLImageElement) => image.naturalWidth)).toBeGreaterThan(0);
    }
    await page.setViewportSize({ width: 1280, height: 900 });
    await scanAccessibility(page, info, `${role}-regression-desktop`);
    await page.setViewportSize({ width: 320, height: 740 });
    await scanAccessibility(page, info, `${role}-regression-mobile-320`);
    await page.getByRole("banner").getByRole("button", { name: "Sign out of your account", exact: true }).click();
    await expect(page).toHaveURL(/\/sign-in$/);
    await expect(page.getByRole("region", { name: "Welcome back", exact: true })).toBeVisible();
  }
});
