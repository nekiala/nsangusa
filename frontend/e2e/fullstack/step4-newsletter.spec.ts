import { english, expect } from "../english";
import type { NewsletterPreference } from "../../lib/newsletter-api";
import {
  administrator, clickMutation, deleteTestAccount, findOnPages, frontend, get, mailLink,
  observeRequests, registerVerified, signIn, test, uniqueAccount, waitForMail
} from "./phase4-helpers";

test("subscriber manages consent-bound preferences and administrator discovers consent evidence", async ({ page, browser }) => {
  const account = uniqueAccount("preferences");
  const adminContext = await browser.newContext({ baseURL: frontend });
  const admin = await adminContext.newPage();
  let registered = false;
  try {
    await registerVerified(page, account);
    registered = true;
    await signIn(page, account);
    await page.goto("/newsletter");
    await page.getByLabel("Email address", { exact: true }).first().fill(account.email);
    await page.getByLabel("Delivery frequency", { exact: true }).first().selectOption("weekly");
    const subscribed = await clickMutation(page, page.getByRole("button", { name: "Subscribe", exact: true }).first(),
      "POST", "/api/v1/newsletter/subscriptions", 202);
    const subscription = await subscribed.json() as { subscriptionId: string };
    const confirmation = await waitForMail(page.request, account.email, /confirm/i);
    await page.goto(mailLink(confirmation, "/newsletter/confirm").href);
    await clickMutation(page, page.getByRole("button", { name: "Confirm subscription", exact: true }),
      "POST", "/api/v1/newsletter/confirm");
    await expect(page.getByRole("status").filter({ hasText: "Your subscription is confirmed." })).toBeVisible();
    await page.goto("/profile");
    await expect(page.getByLabel("Newsletter frequency", { exact: true })).toHaveValue("weekly");
    await page.goto("/newsletter/preferences");
    await page.getByRole("region", { name: "Newsletter preferences" }).getByLabel("Email address", { exact: true }).fill(account.email);
    await clickMutation(page, page.getByRole("button", { name: "Email a preference link", exact: true }),
      "POST", "/api/v1/newsletter/preferences/link", 202);
    const mail = await waitForMail(page.request, account.email, /preferences/i);
    const link = mailLink(mail, "/newsletter/preferences");
    const observed = observeRequests(page, "/api/v1/newsletter/preferences");
    try {
      await page.goto(link.href);
      await expect(page.getByLabel("Email frequency", { exact: true })).toHaveValue("weekly");
      expect(observed.requests.filter((request) => request.method() === "POST")).toHaveLength(0);
      await expect(page).toHaveURL(new URL(english("/newsletter/preferences"), frontend).href);
      await page.getByLabel("Email frequency", { exact: true }).selectOption("daily");
      const saved = await clickMutation(page, page.getByRole("button", { name: "Save newsletter preferences", exact: true }),
        "POST", "/api/v1/newsletter/preferences");
      expect(saved.request().headers()["idempotency-key"]).toBeTruthy();
      await expect(page.getByRole("status").filter({ hasText: "Newsletter frequency updated." })).toBeVisible();
      const privatePath = `/api/v1/newsletter/preferences${link.search}`;
      expect((await get<NewsletterPreference>(page.request, privatePath)).frequency).toBe("daily");
    } finally {
      observed.stop();
    }

    await signIn(admin, administrator, "/admin/newsletter");
    await admin.getByLabel("Subscription status", { exact: true }).selectOption("confirmed");
    await admin.getByLabel("Subscription frequency", { exact: true }).selectOption("daily");
    const row = admin.locator(".queue-list > li").filter({ hasText: subscription.subscriptionId });
    await findOnPages(admin, row, "Subscription pages");
    await expect(row).toContainText("Account linked");
    await expect(row).not.toContainText(account.email);
    await row.getByRole("button", { name: "View consent history", exact: true }).click();
    await expect(row.getByRole("region", { name: "Consent history" })).toContainText("confirmed");
    await row.getByRole("button", { name: "View subscription deliveries", exact: true }).click();
    await expect(admin.getByText("No deliveries match these filters.")).toBeVisible();
  } finally {
    if (registered) await deleteTestAccount(page.request, account);
    await adminContext.close();
  }
});
