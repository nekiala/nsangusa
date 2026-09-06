import { describe, expect, it } from "vitest";
import { generateMetadata as utilityMetadata } from "./[page]/page";
import { generateMetadata as topicMetadata } from "./topics/[topic]/page";
import robots from "./robots";

describe("frontend metadata", () => {
  it("uses human-readable auth titles and prevents indexing", async () => {
    const metadata = await utilityMetadata({ params: Promise.resolve({ page: "sign-in" }), searchParams: Promise.resolve({}) });
    expect(metadata.title).toBe("Sign in");
    expect(metadata.robots).toEqual({ index: false, follow: false });
  });

  it("normalizes topic titles and canonical URLs", async () => {
    const metadata = await topicMetadata({ params: Promise.resolve({ topic: "technology" }) });
    expect(metadata.title).toBe("Technology");
    expect(metadata.alternates).toEqual({ canonical: "/topics/technology" });
  });

  it("keeps every administrative route out of robots", () => {
    expect(robots().rules).toEqual(expect.arrayContaining([
      expect.objectContaining({ disallow: expect.arrayContaining(["/admin"]) })
    ]));
  });
});
