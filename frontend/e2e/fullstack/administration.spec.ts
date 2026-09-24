import { expect, type Page } from "@playwright/test";
import { test } from "./phase4-helpers";

async function signIn(page: Page, destination: string, email: string, password: string) {
  await page.goto(`/sign-in?next=${encodeURIComponent(destination)}`);
  await page.getByRole("region", { name: "Welcome back" }).getByLabel("Email address").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
}

test("real administrator inspects workflow inventory, audit filters and replay controls", async ({ page }) => {
  const email = process.env.FULLSTACK_EDITOR_EMAIL;
  const password = process.env.FULLSTACK_EDITOR_PASSWORD;
  expect(email).toBeTruthy(); expect(password).toBeTruthy();
  await signIn(page, "/admin/operations", email!, password!);
  await expect(page.getByRole("heading", { name: "Operations summary" })).toBeVisible();
  await expect(page.getByText("Unpublished outbox events", { exact: true })).toBeVisible();
  const summary = await page.request.get("/api/v1/admin/operations/summary");
  expect(summary.ok()).toBeTruthy();
  expect(await summary.json()).toMatchObject({ workflow: { pendingOutbox: expect.any(Number), pendingReplays: expect.any(Number) } });

  await page.getByRole("link", { name: "Audit logs", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Audit logs" })).toBeVisible();
  await page.getByLabel("Action", { exact: true }).fill("NO_SUCH_SYNTHETIC_AUDIT_ACTION");
  await page.getByRole("button", { name: "Search audit records" }).click();
  await expect(page.getByText("No audit records match these filters.")).toBeVisible();
  const audit = await page.request.get("/api/v1/admin/audit-records?size=1");
  expect(audit.ok()).toBeTruthy();
  expect(audit.headers()["cache-control"]).toContain("no-store");
  expect(await audit.json()).toMatchObject({ items: expect.any(Array), total: expect.any(Number), size: 1 });

  await page.getByRole("link", { name: "Failed events", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Failed events and replay" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Run replay dry run" })).toBeDisabled();
  const history = await page.request.get("/api/v1/admin/operations/replays?size=1");
  expect(history.ok()).toBeTruthy();
  expect(await history.json()).toMatchObject({ items: expect.any(Array), total: expect.any(Number) });
});

test("reader and editor cannot enter administrator-only pages or APIs", async ({ page, browser }) => {
  const anonymous = await page.request.get("/api/v1/admin/audit-records");
  expect(anonymous.status()).toBe(401);
  for (const [email, password] of [
    ["reader@example.test", "reader-demo-password"],
    ["editor@example.test", "editor-demo-password"]
  ]) {
    const context = await browser.newContext();
    const user = await context.newPage();
    await signIn(user, "/admin/audit", email, password);
    await expect(user.getByRole("heading", { name: "Staff access is required." })).toBeVisible();
    expect((await user.request.get("/api/v1/admin/audit-records")).status()).toBe(403);
    expect((await user.request.get("/api/v1/admin/operations/replays")).status()).toBe(403);
    await context.close();
  }
});
