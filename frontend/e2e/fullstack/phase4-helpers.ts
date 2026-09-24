import { randomUUID } from "node:crypto";
import { setTimeout as pause } from "node:timers/promises";
import { expect, test as base, type APIRequestContext, type Locator, type Page, type Request } from "@playwright/test";
import type { AccountProfile } from "../../lib/identity-api";

export const frontend = process.env.FULLSTACK_FRONTEND_URL || "http://127.0.0.1:13000";
const mailpit = process.env.FULLSTACK_MAILPIT_URL || "http://127.0.0.1:8025";
export const administrator = { email: "admin@example.test", password: "administrator-demo-password" };
export const moderator = { email: "moderator@example.test", password: "moderator-demo-password" };
export type TestAccount = { email: string; password: string; displayName: string };
type MailMessage = { ID: string; Subject: string; Text: string; HTML: string };

export const test = base.extend<{ rateLimitWindow: void }>({
  // Local scenarios share one proxy IP; preserve the backend's fixed-minute limits.
  // eslint-disable-next-line no-empty-pattern
  rateLimitWindow: [async ({}, use) => {
    await pause(60_000 - Date.now() % 60_000 + 1000);
    await use();
  }, { auto: true, timeout: 70_000 }]
});

export function uniqueAccount(purpose: string): TestAccount {
  const suffix = randomUUID().slice(0, 12);
  return {
    email: `phase4-${purpose}-${suffix}@example.test`,
    password: `Phase4-password-${suffix}`,
    displayName: `Phase4 ${purpose} ${suffix}`
  };
}

export async function get<T>(request: APIRequestContext, path: string): Promise<T> {
  const response = await request.get(path);
  expect(response.ok(), `GET ${new URL(path, frontend).pathname}: ${response.status()}`).toBeTruthy();
  return response.json() as Promise<T>;
}

// Each mutation is sent once, with fresh CSRF state and its own request identity.
export async function mutate(request: APIRequestContext, method: string, path: string, data?: unknown) {
  const csrf = await get<{ headerName: string; token: string }>(request, "/api/v1/auth/csrf");
  return request.fetch(path, {
    method, data, maxRedirects: 0,
    headers: { [csrf.headerName]: csrf.token, "Idempotency-Key": randomUUID() }
  });
}

export async function clickMutation(page: Page, button: Locator, method: string, path: string, status = 204) {
  const pending = page.waitForResponse((response) =>
    response.request().method() === method && new URL(response.url()).pathname === path);
  await button.click();
  const response = await pending;
  expect(response.status(), `${method} ${path}`).toBe(status);
  return response;
}

export function observeRequests(page: Page, path: string) {
  const requests: Request[] = [];
  const listener = (request: Request) => {
    if (new URL(request.url()).pathname === path) requests.push(request);
  };
  page.on("request", listener);
  return { requests, stop: () => page.off("request", listener) };
}

export async function signIn(page: Page, account: Pick<TestAccount, "email" | "password">, destination = "/profile") {
  await page.goto(`/sign-in?next=${encodeURIComponent(destination)}`);
  const form = page.getByRole("region", { name: "Welcome back" });
  await form.getByLabel("Email address", { exact: true }).fill(account.email);
  await form.getByLabel("Password", { exact: true }).fill(account.password);
  await clickMutation(page, form.getByRole("button", { name: "Sign in", exact: true }), "POST", "/api/v1/auth/login");
  await expect(page).toHaveURL(new URL(destination, frontend).href);
}

export async function rejectedLogin(page: Page, account: Pick<TestAccount, "email" | "password">) {
  await page.goto("/sign-in");
  const form = page.getByRole("region", { name: "Welcome back" });
  await form.getByLabel("Email address", { exact: true }).fill(account.email);
  await form.getByLabel("Password", { exact: true }).fill(account.password);
  await clickMutation(page, form.getByRole("button", { name: "Sign in", exact: true }), "POST", "/api/v1/auth/login", 401);
  await expect(form.getByRole("alert")).toBeVisible();
  expect((await page.request.get("/api/v1/auth/me")).status()).toBe(401);
}

export async function waitForMail(request: APIRequestContext, recipient: string, subject: RegExp) {
  let messageId = "";
  await expect.poll(async () => {
    const result = await get<{ messages: { ID: string; Subject: string }[] }>(
      request, `${mailpit}/api/v1/search?query=${encodeURIComponent(`to:${recipient}`)}`);
    messageId = result.messages.find((message) => subject.test(message.Subject))?.ID || "";
    return messageId;
  }, { timeout: 60_000, intervals: [500, 1000, 2000] }).not.toBe("");
  return get<MailMessage>(request, `${mailpit}/api/v1/message/${messageId}`);
}

export function mailLink(message: MailMessage, path: string): URL {
  const links = `${message.Text}\n${message.HTML}`.replaceAll("&amp;", "&").match(/https?:\/\/[^\s"'<>]+/g) || [];
  const link = links.map((value) => new URL(value)).find((url) => url.pathname === path);
  expect(link, `Mail must contain the browser ${path} link`).toBeDefined();
  expect(link!.origin).toBe(new URL(frontend).origin);
  expect(link!.searchParams.get("token")).toBeTruthy();
  return link!;
}

export async function registerVerified(page: Page, account: TestAccount, browserRegistration = false) {
  if (browserRegistration) {
    await page.goto("/register");
    const form = page.getByRole("region", { name: "Create your account" });
    await form.getByLabel("Name", { exact: true }).fill(account.displayName);
    await form.getByLabel("Email address", { exact: true }).fill(account.email);
    await form.getByLabel("Password", { exact: true }).fill(account.password);
    const response = await clickMutation(page, form.getByRole("button", { name: "Register", exact: true }),
      "POST", "/api/v1/auth/register", 202);
    expect(await response.json()).toEqual({ status: "verification_required" });
    await expect(form.getByRole("status")).toHaveText("Check your inbox to verify your account.");
  } else {
    const response = await mutate(page.request, "POST", "/api/v1/auth/register", account);
    expect(response.status()).toBe(202);
    expect(await response.json()).toEqual({ status: "verification_required" });
  }
  const mail = await waitForMail(page.request, account.email, /verify.*account/i);
  const completion = observeRequests(page, "/api/v1/auth/verify-email");
  try {
    await page.goto(mailLink(mail, "/verify-email").href);
    await expect(page.getByRole("heading", { name: "Verify your email", exact: true })).toBeVisible();
    expect(completion.requests).toHaveLength(0);
    await clickMutation(page, page.getByRole("button", { name: "Verify email address", exact: true }),
      "POST", "/api/v1/auth/verify-email");
    await expect(page.getByRole("status").filter({ hasText: "Your email is verified." })).toBeVisible();
    expect(completion.requests).toHaveLength(1);
    await expect(page).toHaveURL(new URL("/verify-email", frontend).href);
  } finally {
    completion.stop();
  }
}

export async function deleteTestAccount(request: APIRequestContext, account: TestAccount) {
  expect(account.email).toMatch(/^phase4-[a-z]+-[a-f0-9-]+@example\.test$/);
  const csrf = await get<{ headerName: string; token: string }>(request, "/api/v1/auth/csrf");
  const login = await request.post("/api/v1/auth/login", {
    form: { username: account.email, password: account.password },
    headers: { [csrf.headerName]: csrf.token }, maxRedirects: 0
  });
  expect(login.status()).toBe(204);
  const profile = await get<AccountProfile>(request, "/api/v1/auth/me");
  expect(profile.email).toBe(account.email);
  expect(profile.roles).not.toContain("ADMINISTRATOR");
  const deletion = await mutate(request, "DELETE", "/api/v1/auth/me", { confirmation: "DELETE", expectedVersion: profile.version });
  expect(deletion.status()).toBe(204);
}

export async function findOnPages(page: Page, target: Locator, pagination: string) {
  const navigation = page.getByRole("navigation", { name: pagination, exact: true });
  await expect(navigation).toBeVisible();
  for (let index = 0; index < 100; index++) {
    if (await target.count()) {
      await expect(target).toBeVisible();
      return;
    }
    const next = navigation.getByRole("button", { name: "Next page", exact: true });
    expect(await next.isEnabled(), `Could not discover the test record in ${pagination}`).toBe(true);
    const previous = await navigation.textContent();
    await next.click();
    await expect(navigation).not.toHaveText(previous!);
    await expect(page.getByText(/^Loading (comments|subscriptions|policy)…$/)).toHaveCount(0);
  }
  throw new Error(`Record was not found within 100 ${pagination}`);
}
