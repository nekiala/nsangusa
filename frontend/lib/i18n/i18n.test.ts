import { describe, expect, it } from "vitest";
import { alternates, formatDate, localizePath, plural, splitLocale, translator } from ".";
import { en } from "./en";
import { fr } from "./fr";

describe("localisation", () => {
  it("serves French at the root and English under /en", () => {
    expect(splitLocale("/latest")).toEqual({ locale: "fr", pathname: "/latest", prefixed: false });
    expect(splitLocale("/en")).toEqual({ locale: "en", pathname: "/", prefixed: true });
    expect(splitLocale("/en/articles/a")).toEqual({ locale: "en", pathname: "/articles/a", prefixed: true });
    expect(splitLocale("/english")).toMatchObject({ locale: "fr", pathname: "/english" });
  });

  it("prefixes only site-internal paths", () => {
    expect(localizePath("/en", "/")).toBe("/en");
    expect(localizePath("/en", "/search?q=a")).toBe("/en/search?q=a");
    expect(localizePath("/en", "https://x.com/a")).toBe("https://x.com/a");
    expect(localizePath("/en", "//evil.example")).toBe("//evil.example");
    expect(localizePath("", "/latest")).toBe("/latest");
  });

  it("advertises both languages for a page", () => {
    expect(alternates("/en", "/topics")).toEqual({
      canonical: "/en/topics", languages: { fr: "/topics", en: "/en/topics", "x-default": "/topics" }
    });
  });

  it("translates, interpolates and pluralises by locale", () => {
    const t = translator("fr");
    expect(t("session.account", { name: "Ada" })).toBe("Votre compte : Ada");
    expect(plural("fr", t, "published", 0)).toBe("0 article publié");
    expect(plural("en", translator("en"), "published", 0)).toBe("0 published articles");
    expect(formatDate("2026-10-02T00:00:00Z", "fr")).toBe("2 octobre 2026");
  });

  it("keeps the catalogues complete", () => {
    expect(Object.keys(fr).sort()).toEqual(Object.keys(en).sort());
    for (const key of Object.keys(en) as (keyof typeof en)[]) {
      expect(fr[key].match(/\{\w+\}/g)?.sort()).toEqual(en[key].match(/\{\w+\}/g)?.sort());
    }
  });
});
