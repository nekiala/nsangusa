import { expect } from "@playwright/test";
import { administrator, moderator, mutate, signIn, test } from "./phase4-helpers";

for (const { role, account, links } of [
  { role: "administrator", account: administrator, links: ["Administration", "Editor", "Moderation"] },
  { role: "editor", account: { email: "editor@example.test", password: "editor-demo-password" }, links: ["Editor"] },
  { role: "moderator", account: moderator, links: ["Moderation"] },
  { role: "reader", account: { email: "reader@example.test", password: "reader-demo-password" }, links: [] }
]) {
  test(`${role} sees cookie-backed account navigation across history and sign-out`, async ({ page }) => {
    try {
      await signIn(page, account);
      const header = page.getByRole("banner");
      const workspaces = header.getByRole("navigation", { name: "Your workspaces" });
      async function expectSession() {
        await expect(header.getByRole("link", { name: /^Your account:/ })).toBeVisible();
        await expect(header.getByRole("link", { name: "Sign in", exact: true })).toHaveCount(0);
        if (links.length) await expect(workspaces.getByRole("link")).toHaveText(links);
        else await expect(workspaces).toHaveCount(0);
      }
      await expect(page.getByRole("heading", { name: "Profile and account settings" })).toBeVisible();
      await expectSession();
      await header.getByRole("link", { name: "Nsangusa home" }).click();
      await expect(page).toHaveURL(/\/$/);
      await expectSession();
      await page.goBack();
      await expect(page).toHaveURL(/\/profile$/);
      await expectSession();
      await page.goForward();
      await expect(page).toHaveURL(/\/$/);
      await expectSession();
      await page.reload();
      await expectSession();
      expect((await page.request.get("/api/v1/auth/me")).status()).toBe(200);
      expect(await page.evaluate(() => sessionStorage.getItem("nsangusa.fake-user"))).toBeNull();
      await page.goto("/sign-in");
      await expect(page.getByRole("heading", { name: "You are already signed in" })).toBeVisible();
      await expect(page.getByLabel("Password", { exact: true })).toHaveCount(0);
      await page.getByRole("link", { name: "Continue to your account" }).click();
      if (links.length) {
        await workspaces.getByRole("link", { name: links[0], exact: true }).click();
        await expect(page.getByRole("navigation", { name: "Staff workspace" })).toBeVisible();
      } else {
        await page.goto("/admin");
        await expect(page.getByRole("heading", { name: "Staff access is required." })).toBeVisible();
      }
      await expectSession();
      await header.getByRole("button", { name: "Sign out of your account" }).click();
      await expect(page).toHaveURL(/\/sign-in$/);
      await expect(header.getByRole("link", { name: "Sign in", exact: true })).toBeVisible();
      await expect(workspaces).toHaveCount(0);
      expect((await page.request.get("/api/v1/auth/me")).status()).toBe(401);
      await page.goBack();
      await expect(header.getByRole("link", { name: "Sign in", exact: true })).toBeVisible();
      await expect(workspaces).toHaveCount(0);
      await page.goto("/profile");
      await expect(page).toHaveURL(/\/sign-in\?next=%2Fprofile$/);
      await expect(page.getByRole("heading", { name: "Profile and account settings" })).toHaveCount(0);
    } finally {
      expect((await mutate(page.request, "POST", "/api/v1/auth/logout")).status()).toBe(204);
    }
  });
}
