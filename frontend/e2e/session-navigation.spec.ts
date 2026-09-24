import { expect, test, type Page } from "@playwright/test";
import { scanAccessibility, tabTo } from "./accessibility-helpers";

async function signIn(page: Page, account: string) {
  await page.goto("/sign-in");
  const form = page.getByRole("region", { name: "Welcome back" });
  await form.getByLabel("Email address").fill(`${account}@example.test`);
  await form.getByLabel("Password").fill("a-secure-password");
  await form.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/profile$/);
  await expect(page.getByRole("banner").getByRole("button", { name: "Sign out of your account" })).toBeVisible();
}

for (const { account, workspaces } of [
  { account: "admin", workspaces: ["Administration", "Editor", "Moderation"] },
  { account: "editor", workspaces: ["Editor"] },
  { account: "moderator", workspaces: ["Moderation"] },
  { account: "reader", workspaces: [] }
]) {
  test(`${account} retains signed-in navigation through public pages, history, reload and sign-in revisits`, async ({ page }) => {
    await signIn(page, account);
    const header = page.getByRole("banner");
    const navigation = header.getByRole("navigation", { name: "Your workspaces" });
    async function expectAccount() {
      await expect(header.getByRole("link", { name: /^Your account:/ })).toBeVisible();
      await expect(header.getByRole("link", { name: "Sign in", exact: true })).toHaveCount(0);
      if (workspaces.length) await expect(navigation.getByRole("link")).toHaveText(workspaces);
      else await expect(navigation).toHaveCount(0);
    }
    await expectAccount();
    await header.getByRole("link", { name: "Nsangusa home" }).click();
    await expect(page).toHaveURL(/\/$/);
    await expectAccount();
    await page.goBack();
    await expect(page).toHaveURL(/\/profile$/);
    await expectAccount();
    await page.goForward();
    await expect(page).toHaveURL(/\/$/);
    await expectAccount();
    await page.reload();
    await expectAccount();
    await page.goto("/sign-in");
    await expect(page.getByRole("heading", { name: "You are already signed in" })).toBeVisible();
    await expect(page.getByLabel("Password", { exact: true })).toHaveCount(0);
    await page.getByRole("link", { name: "Continue to your account" }).click();
    await expect(page.getByRole("heading", { name: "Profile and account settings" })).toBeVisible();
    if (workspaces.length) {
      await navigation.getByRole("link", { name: workspaces[0], exact: true }).click();
      await expect(page.getByRole("navigation", { name: "Staff workspace" })).toBeVisible();
      await expectAccount();
    }
    await header.getByRole("button", { name: "Sign out of your account" }).click();
    await expect(page).toHaveURL(/\/sign-in$/);
    await expect(header.getByRole("link", { name: "Sign in", exact: true })).toBeVisible();
    await expect(navigation).toHaveCount(0);
    await page.goBack();
    await expect(header.getByRole("link", { name: "Sign in", exact: true })).toBeVisible();
    await expect(navigation).toHaveCount(0);
    await page.goto("/profile");
    await expect(page).toHaveURL(/\/sign-in\?next=%2Fprofile$/);
    await expect(page.getByRole("heading", { name: "Profile and account settings" })).toHaveCount(0);
  });
}

test("profile updates, current-session revocation and account deletion keep the header coherent", async ({ page }) => {
  await signIn(page, "reader");
  await page.getByLabel("Display name").fill("Navigation reader");
  await page.getByRole("button", { name: "Save profile" }).click();
  await expect(page.getByText("Profile saved.", { exact: true })).toBeVisible();
  await expect(page.getByRole("banner").getByRole("link", { name: "Your account: Navigation reader" })).toBeVisible();
  await page.getByRole("button", { name: "Sign out this session" }).click();
  await expect(page).toHaveURL(/\/sign-in$/);
  await expect(page.getByRole("banner").getByRole("link", { name: "Sign in", exact: true })).toBeVisible();
  await signIn(page, "another-reader");
  await page.getByLabel("Type DELETE to confirm account deletion").fill("DELETE");
  await page.getByRole("button", { name: "Permanently delete account" }).click();
  await expect(page).toHaveURL(/\/sign-in\?deleted=1$/);
  await expect(page.getByRole("banner").getByRole("link", { name: "Sign in", exact: true })).toBeVisible();
});

test("administrator workspace shortcuts reflow and are keyboard accessible", async ({ page }, info) => {
  await page.setViewportSize({ width: 320, height: 740 });
  await signIn(page, "admin");
  await page.goto("/");
  const workspaces = page.getByRole("navigation", { name: "Your workspaces" });
  await expect(workspaces).toBeVisible();
  await scanAccessibility(page, info, "signed-in-navigation-mobile-320");
  await tabTo(page, workspaces.getByRole("link", { name: "Editor", exact: true }));
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/admin\/editor$/);
  await expect(page.getByRole("navigation", { name: "Staff workspace" })).toBeVisible();
});
