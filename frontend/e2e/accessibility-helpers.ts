import AxeBuilder from "@axe-core/playwright";
import { writeFile } from "node:fs/promises";
import { expect, type Locator, type Page, type TestInfo } from "@playwright/test";

export async function scanAccessibility(page: Page, info: TestInfo, name: string) {
  await expect(page).toHaveTitle(/\S/);
  await expect(page.getByRole("main")).toHaveCount(1);
  await expect(page.getByRole("banner")).toHaveCount(1);
  await expect(page.getByRole("contentinfo")).toHaveCount(1);
  await expect(page.getByRole("heading", { level: 1 })).toHaveCount(1);
  const results = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa", "best-practice"])
    .analyze();
  const report = info.outputPath(`axe-${name}.json`);
  await writeFile(report, JSON.stringify(results, null, 2));
  await info.attach(`axe-${name}`, { path: report, contentType: "application/json" });
  const blockers = results.violations.filter(({ impact }) => impact === "critical" || impact === "serious");
  expect(blockers, `${name}: critical/serious WCAG findings; inspect the complete axe attachment, including incomplete checks`).toEqual([]);
  const headings = await page.getByRole("heading").evaluateAll((elements) =>
    elements.filter((element) => (element as HTMLElement).checkVisibility()).map((element) => Number(element.tagName.slice(1))));
  for (let index = 1; index < headings.length; index++) {
    expect(headings[index], `${name}: heading levels must not skip a level`).toBeLessThanOrEqual(headings[index - 1] + 1);
  }
  await expectNoHorizontalOverflow(page);
}

export async function expectNoHorizontalOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth),
    "The page must reflow without document-level horizontal scrolling").toBeLessThanOrEqual(1);
}

export async function tabTo(page: Page, target: Locator, limit = 100) {
  for (let index = 0; index < limit; index++) {
    await page.keyboard.press("Tab");
    if (await target.evaluate((element) => element === document.activeElement)) {
      await expectVisibleFocus(target);
      return;
    }
  }
  throw new Error(`Could not reach ${await target.getAttribute("id") || await target.textContent()} using ${limit} Tab presses`);
}

export async function expectVisibleFocus(target: Locator) {
  await expect(target).toBeFocused();
  const outline = await target.evaluate((element) => {
    const style = getComputedStyle(element);
    return { width: parseFloat(style.outlineWidth), style: style.outlineStyle, color: style.outlineColor };
  });
  expect(outline.width).toBeGreaterThanOrEqual(2);
  expect(outline.style).not.toBe("none");
  expect(outline.color).not.toBe("rgba(0, 0, 0, 0)");
}

export async function expectReducedMotion(page: Page) {
  await page.emulateMedia({ reducedMotion: "reduce" });
  expect(await page.evaluate(() => matchMedia("(prefers-reduced-motion: reduce)").matches)).toBe(true);
  expect(await page.evaluate(() => document.getAnimations().filter((animation) => animation.playState === "running").length)).toBe(0);
  expect(await page.locator("button, a, input, select, textarea").evaluateAll((elements) => elements.every((element) => {
    const style = getComputedStyle(element);
    return style.animationName === "none" && style.transitionDuration.split(",").every((duration) => parseFloat(duration) === 0);
  }))).toBe(true);
}

export async function publicKeyboardJourney(page: Page, info: TestInfo) {
  await page.goto("/");
  await tabTo(page, page.getByRole("link", { name: "Skip to content", exact: true }), 1);
  await page.keyboard.press("Enter");
  await expect(page.getByRole("main")).toBeFocused();
  await scanAccessibility(page, info, "public-desktop");
  await page.setViewportSize({ width: 320, height: 740 });
  await page.goto("/newsletter");
  await scanAccessibility(page, info, "newsletter-mobile-320");
  const footerEmail = page.getByRole("contentinfo").getByLabel("Email address", { exact: true });
  await tabTo(page, footerEmail);
  expect(await footerEmail.evaluate((element) => getComputedStyle(element).outlineColor)).toBe("rgb(247, 244, 237)");
  await expectReducedMotion(page);
  await page.goto("/register");
  await scanAccessibility(page, info, "registration-mobile-320");
  await page.goto("/search");
  const search = page.getByRole("searchbox");
  await tabTo(page, search);
  await page.keyboard.type("library");
  const result = page.waitForURL(/\/search\?q=library/);
  await page.keyboard.press("Enter");
  await result;
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  await scanAccessibility(page, info, "search-mobile-320");
}
