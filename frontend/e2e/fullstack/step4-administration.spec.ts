import { expect } from "@playwright/test";
import type { AccountProfile, AdminUser } from "../../lib/identity-api";
import type { AiConfiguration, AiPromptVersion, AiProvider, AiProviderSetup } from "../../lib/ai-admin-api";
import {
  administrator, clickMutation, deleteTestAccount, frontend, get, mutate,
  registerVerified, signIn, test, uniqueAccount
} from "./phase4-helpers";

test("administrator discovers a user, changes roles with confirmation and revokes the old session", async ({ page, browser }) => {
  const account = uniqueAccount("roles");
  const readerContext = await browser.newContext({ baseURL: frontend });
  const reader = await readerContext.newPage();
  let registered = false;
  try {
    await registerVerified(reader, account);
    registered = true;
    await signIn(reader, account);
    const profile = await get<AccountProfile>(reader.request, "/api/v1/auth/me");
    await signIn(page, administrator, "/admin/users");
    await page.getByLabel("Search users by name or email", { exact: true }).fill(account.email);
    await page.getByRole("button", { name: "Search users", exact: true }).click();
    await page.getByRole("button", { name: `Manage roles for ${account.displayName}`, exact: true }).click();
    const original = await get<AdminUser>(page.request, `/api/v1/admin/users/${profile.id}`);
    await page.getByRole("checkbox", { name: "EDITOR", exact: true }).check();
    await expect(page.getByRole("button", { name: "Save roles", exact: true })).toBeDisabled();
    await page.getByLabel(`Type ${account.email} to confirm`, { exact: true }).fill(account.email);
    const response = await clickMutation(page, page.getByRole("button", { name: "Save roles", exact: true }),
      "PUT", `/api/v1/admin/users/${profile.id}/roles`);
    expect(response.request().postDataJSON()).toMatchObject({ expectedVersion: original.version, confirmation: account.email, roles: ["EDITOR", "READER"] });
    expect(response.request().headers()["idempotency-key"]).toBeTruthy();
    await expect(page.getByText("Roles updated. Existing sessions are revoked.", { exact: true }).first()).toBeVisible();
    expect((await reader.request.get("/api/v1/auth/me")).status()).toBe(401);
    const updated = await get<AdminUser>(page.request, `/api/v1/admin/users/${profile.id}`);
    expect(updated.roles).toEqual(expect.arrayContaining(["READER", "EDITOR"]));
    expect(updated.version).toBeGreaterThan(original.version);
    const stale = await mutate(page.request, "PUT", `/api/v1/admin/users/${profile.id}/roles`,
      { expectedVersion: original.version, confirmation: account.email, roles: ["READER"] });
    expect(stale.status()).toBe(409);
    await signIn(reader, account, "/admin/editor");
    await expect(reader.getByRole("heading", { name: "Articles", exact: true })).toBeVisible();
    expect((await reader.request.get("/api/v1/admin/users")).status()).toBe(403);
  } finally {
    if (registered) await deleteTestAccount(reader.request, account);
    await readerContext.close();
  }
});

test("administrator creates immutable guidance and explicitly selects it without changing safety or secrets", async ({ page }) => {
  await signIn(page, administrator, "/admin/ai");
  const root = "/api/v1/admin/ai-configuration";
  const original = await get<AiConfiguration>(page.request, root);
  const providers = await get<AiProvider[]>(page.request, `${root}/providers`);
  expect(providers.find((provider) => provider.id === original.provider)?.simulated,
    "The active acceptance provider must be simulated; catalog availability is not activation").toBe(true);
  const setup = await get<AiProviderSetup>(page.request, `${root}/setup`);
  expect(setup.liveEnabled, "Acceptance must prohibit live provider calls").toBe(false);
  expect(setup.liveActive).toBe(false);
  const selection = page.getByRole("region", { name: "Active generation configuration" });
  const guidance = `Use concise attributed paragraphs and preserve uncertainty. Local editorial acceptance ${Date.now()}.`;
  let selected = false;
  try {
    await expect(page.getByRole("heading", { name: "AI configuration", exact: true })).toBeVisible();
    await expect(selection.getByLabel(/API key|Base URL|Secret reference/i)).toHaveCount(0);
    await expect(page.getByLabel(/Base URL|Secret reference/i)).toHaveCount(0);
    await page.getByLabel("Editorial guidance", { exact: true }).fill(guidance);
    await page.getByLabel("Prompt change reason", { exact: true }).fill("Local Step 4 editorial acceptance");
    const created = await clickMutation(page, page.getByRole("button", { name: "Create prompt version", exact: true }),
      "POST", `${root}/prompts`, 201);
    const prompt = await created.json() as AiPromptVersion;
    expect(prompt.guidance).toBe(guidance);
    expect((await get<AiConfiguration>(page.request, root)).promptVersion).toBe(original.promptVersion);
    await expect(selection.getByRole("option", { name: prompt.version, exact: true })).toHaveCount(1);
    await selection.getByLabel("Prompt version", { exact: true }).selectOption(prompt.version);
    await selection.getByLabel("Configuration change reason", { exact: true }).fill("Select reviewed local guidance");
    const applied = await clickMutation(page, selection.getByRole("button", { name: "Apply configuration", exact: true }), "PUT", root, 200);
    selected = true;
    const configuration = await applied.json() as AiConfiguration;
    expect(configuration).toMatchObject({ promptVersion: prompt.version, provider: original.provider, model: original.model });
    expect(configuration.version).toBeGreaterThan(original.version);
    expect(applied.request().headers()["idempotency-key"]).toBeTruthy();
    await expect(page.getByRole("region", { name: "Configuration audit history" })).toContainText("Select reviewed local guidance");
    const stale = await mutate(page.request, "PUT", root, {
      expectedVersion: original.version, provider: original.provider, model: original.model,
      promptVersion: original.promptVersion, reason: "A stale selection must not overwrite"
    });
    expect(stale.status()).toBe(409);
    expect((await get<AiPromptVersion>(page.request, `${root}/prompts/${encodeURIComponent(prompt.version)}`)).guidance).toBe(guidance);
  } finally {
    if (selected) {
      const current = await get<AiConfiguration>(page.request, root);
      const restored = await mutate(page.request, "PUT", root, {
        expectedVersion: current.version, provider: original.provider, model: original.model,
        promptVersion: original.promptVersion, reason: "Restore original local acceptance configuration"
      });
      expect(restored.status()).toBe(200);
    }
  }
});
