import { expect, test as base, type Browser, type BrowserContext, type Page } from "@playwright/test";

/**
 * The address of a page on the English site. French is the default language at the root; these
 * suites assert the English catalogue, so their page navigations use the /en pages. API routes,
 * feeds and absolute URLs are left alone.
 */
export function english(url: string) {
  if (!url.startsWith("/") || url.startsWith("/api/") || url === "/en" || url.startsWith("/en/") || url.startsWith("/en?")) return url;
  return url === "/" ? "/en" : `/en${url}`;
}

const localized = new WeakSet<object>();

function englishPage(page: Page) {
  if (localized.has(page)) return page;
  localized.add(page);
  const goto = page.goto.bind(page);
  page.goto = (url, options) => goto(english(url), options);
  return page;
}

function englishContext(context: BrowserContext) {
  if (localized.has(context)) return context;
  localized.add(context);
  const newPage = context.newPage.bind(context);
  context.newPage = async () => englishPage(await newPage());
  return context;
}

function englishBrowser(browser: Browser) {
  if (localized.has(browser)) return browser;
  localized.add(browser);
  const newContext = browser.newContext.bind(browser);
  browser.newContext = async (options) => englishContext(await newContext(options));
  const newPage = browser.newPage.bind(browser);
  browser.newPage = async (options) => englishPage(await newPage(options));
  return browser;
}

/** Every page a test opens, including those in contexts it creates itself, navigates the English site. */
export const test = base.extend<object, object>({
  browser: [async ({ browser }, provide) => { await provide(englishBrowser(browser)); }, { scope: "worker" }],
  context: async ({ context }, provide) => { await provide(englishContext(context)); },
  page: async ({ page }, provide) => { await provide(englishPage(page)); }
});

export { expect };
