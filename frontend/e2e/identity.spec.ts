import { expect, test } from "@playwright/test";

test("verification and recovery links require explicit completion", async ({ page }) => {
  await page.goto(`/verify-email?token=${"v".repeat(43)}`);
  await expect(page.getByRole("button", { name: "Verify email address" })).toBeVisible();
  await expect(page.getByText("Your email is verified. You can now sign in.")).toHaveCount(0);
  await page.getByRole("button", { name: "Verify email address" }).click();
  await expect(page.getByText("Your email is verified. You can now sign in.")).toBeVisible();
  await expect(page).toHaveURL(/\/verify-email$/);
  await page.goto(`/password-reset?token=${"p".repeat(43)}`);
  await expect(page.getByRole("heading", { name: "Choose a new password" })).toBeVisible();
  await page.getByLabel("New password", { exact: true }).fill("a-new-reader-password");
  await page.getByLabel("Confirm new password").fill("a-new-reader-password");
  await page.getByRole("button", { name: "Reset password", exact: true }).click();
  await expect(page.getByRole("link", { name: "Sign in with your new password" })).toBeVisible();
  await expect(page).toHaveURL(/\/password-reset$/);
});

test("current reader can update preferences and revoke a session without entering identifiers", async ({ page }) => {
  await page.goto("/sign-in?next=%2Fprofile");
  await page.getByRole("region", { name: "Welcome back" }).getByLabel("Email address", { exact: true }).fill("reader@example.test");
  await page.getByLabel("Password", { exact: true }).fill("reader-demo-password");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Profile and account settings" })).toBeVisible();
  await page.getByLabel("Display name").fill("Browser reader");
  await page.getByLabel("Newsletter frequency").selectOption("daily");
  await page.getByRole("button", { name: "Save profile" }).click();
  await expect(page.getByText("Profile saved.", { exact: true })).toBeVisible();
  await expect(page.getByLabel("Display name")).toHaveValue("Browser reader");
  await page.getByRole("button", { name: "Revoke session", exact: true }).click();
  await expect(page.getByRole("button", { name: "Revoke session", exact: true })).toHaveCount(0);
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download account data" }).click();
  await expect((await download).suggestedFilename()).toBe("account-data.json");
});

test("administrator selects an account and explicitly confirms a versioned role change", async ({ page }) => {
  await page.goto("/sign-in?next=%2Fadmin%2Fusers");
  await page.getByRole("region", { name: "Welcome back" }).getByLabel("Email address", { exact: true }).fill("admin@example.test");
  await page.getByLabel("Password", { exact: true }).fill("administrator-demo-password");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Users and roles" })).toBeVisible();
  await page.getByRole("button", { name: "Manage roles for Reader", exact: true }).click();
  await page.getByRole("checkbox", { name: "EDITOR", exact: true }).check();
  await expect(page.getByRole("button", { name: "Save roles", exact: true })).toBeDisabled();
  await page.getByLabel("Type reader@example.test to confirm").fill("reader@example.test");
  await page.getByRole("button", { name: "Save roles", exact: true }).click();
  await expect(page.getByText("Roles updated. Existing sessions are revoked.", { exact: true })).toBeVisible();
});
