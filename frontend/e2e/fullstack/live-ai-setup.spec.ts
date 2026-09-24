import { expect } from "@playwright/test";
import type { AiConfiguration, AiProviderSetup } from "../../lib/ai-admin-api";
import { administrator, get, mutate, signIn, test } from "./phase4-helpers";
import { scanAccessibility } from "../accessibility-helpers";

const root = "/api/v1/admin/ai-configuration";

test("administrator can prepare encrypted OpenAI settings without enabling paid calls", async ({ page }, info) => {
  expect(process.env.FULLSTACK_ISOLATED_ACCEPTANCE,
    "Credential mutation coverage requires the disposable acceptance runner, never the persistent preview").toBe("true");
  await signIn(page, administrator, "/admin/ai");
  const original = await get<AiConfiguration>(page.request, root);
  const initial = await get<AiProviderSetup>(page.request, `${root}/setup`);
  expect(original.provider).toBe("fake");
  expect(initial.liveEnabled).toBe(false);
  expect(initial.masterKeyConfigured).toBe(true);
  expect(initial.credentialStatus).toBe("NOT_CONFIGURED");
  const secret = "sk-qualification-local-only-not-an-openai-project-key";
  const setup = page.getByRole("region", { name: "Provider setup and activation" });
  try {
    await setup.getByLabel("Draft provider type").selectOption("openai");
    await setup.getByLabel("Draft settings reason").fill("Prepare a bounded local configuration with live calls disabled");
    await setup.getByRole("button", { name: "Save provider draft", exact: true }).click();
    await expect(setup).toContainText("Saved draft: openai / gpt-5-mini");
    await setup.getByLabel("New OpenAI API key").fill(secret);
    await setup.getByLabel("Credential change reason").fill("Exercise encrypted storage using a synthetic non-provider credential");
    await setup.getByRole("button", { name: "Store or rotate API key", exact: true }).click();
    await expect(setup).toContainText("Credential: configured");
    await expect(setup.getByLabel("New OpenAI API key")).toHaveValue("");
    const configured = await get<AiProviderSetup>(page.request, `${root}/setup`);
    expect(configured.credentialStatus).toBe("CONFIGURED");
    expect(configured.liveActive).toBe(false);
    expect(configured.canActivate).toBe(false);
    expect(JSON.stringify(configured)).not.toContain(secret);
    expect(await page.content()).not.toContain(secret);
    await setup.getByRole("checkbox").check();
    await setup.getByLabel("Activation reason").fill("An administrator cannot override the disabled operator live gate");
    await expect(setup.getByRole("button", { name: "Activate saved provider draft", exact: true })).toBeDisabled();
    const denied = await mutate(page.request, "POST", `${root}/setup/activate`, {
      expectedVersion: configured.version, expectedConfigurationVersion: original.version,
      acknowledgeOutboundDataAndCost: true, reason: "Prove the disabled operator gate remains authoritative"
    });
    expect(denied.status()).toBe(400);
    expect((await get<AiProviderSetup>(page.request, `${root}/setup`)).liveActive).toBe(false);
    expect((await get<AiConfiguration>(page.request, root)).provider).toBe("fake");
    await page.setViewportSize({ width: 320, height: 740 });
    await scanAccessibility(page, info, "live-ai-setup-disabled-gate-mobile-320");
  } finally {
    let current = await get<AiProviderSetup>(page.request, `${root}/setup`);
    if (current.credentialStatus !== "NOT_CONFIGURED") {
      const removal = await mutate(page.request, "DELETE", `${root}/setup/credential`, {
        expectedVersion: current.version, reason: "Remove the synthetic qualification credential"
      });
      expect(removal.status()).toBe(200);
      current = await removal.json() as AiProviderSetup;
    }
    const restored = await mutate(page.request, "PUT", `${root}/setup`, {
      expectedVersion: current.version, provider: original.provider, model: original.model,
      promptVersion: original.promptVersion, ...initial.limits,
      reason: "Return the isolated setup draft to its original fake provider"
    });
    expect(restored.status()).toBe(200);
    expect((await mutate(page.request, "POST", "/api/v1/auth/logout")).status()).toBe(204);
  }
});
