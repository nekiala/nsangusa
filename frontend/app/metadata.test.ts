import { afterEach, describe, expect, it, vi } from "vitest";
import { generateMetadata as utilityMetadata } from "./[page]/page";
import { generateMetadata as topicMetadata } from "./topics/[topic]/page";
import robots from "./robots";
import { generateMetadata as rootMetadata } from "./layout";

afterEach(() => vi.unstubAllEnvs());

describe("frontend metadata", () => {
  it("uses human-readable auth titles and prevents indexing", async () => {
    const metadata = await utilityMetadata({ params: Promise.resolve({ page: "sign-in" }), searchParams: Promise.resolve({}) });
    expect(metadata.title).toBe("Sign in");
    expect(metadata.robots).toEqual({ index: false, follow: false });
  });

  it("normalizes topic titles and canonical URLs", async () => {
    const metadata = await topicMetadata({ params: Promise.resolve({ topic: "technology" }) });
    expect(metadata.title).toBe("Technology");
    expect(metadata.alternates).toMatchObject({ canonical: "/topics/technology", languages: { fr: "/topics/technology", en: "/en/topics/technology" } });
  });

  it("keeps every administrative route out of robots", () => {
    expect(robots().rules).toEqual(expect.arrayContaining([
      expect.objectContaining({ disallow: expect.arrayContaining(["/admin"]) })
    ]));
  });

  it("uses the runtime publication origin for inherited metadata and robots, not the compiled fallback", async () => {
    vi.stubEnv("NEXT_PUBLIC_SITE_URL", "http://build-time.invalid");
    vi.stubEnv("PUBLIC_BASE_URL", "https://publication.example.test");
    expect((await rootMetadata()).metadataBase).toEqual(new URL("https://publication.example.test/"));
    expect(robots().sitemap).toBe("https://publication.example.test/sitemap.xml");
  });
});
