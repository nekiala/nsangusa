import { describe, expect, it } from "vitest";
import { policyVersion, publicationPolicies } from "./publication-policies";

const configured = {
  PUBLICATION_PUBLISHER: "The Local Publisher",
  PUBLICATION_CONTACT_EMAIL: "editor@publication.test",
  PUBLICATION_PRIVACY_EMAIL: "privacy@publication.test",
  PUBLICATION_JURISDICTION: "Publisher-approved jurisdiction",
  PUBLICATION_RETENTION_SUMMARY: "Contact the publisher for the approved per-category retention schedule.",
  PUBLICATION_POLICY_EFFECTIVE_DATE: "2026-09-18",
  PUBLICATION_POLICY_APPROVED_VERSION: policyVersion
};

describe("publication policy configuration", () => {
  it("keeps unconfigured content explicitly draft without inventing legal identity or contacts", () => {
    const policies = publicationPolicies({});
    expect(policies.approved).toBe(false);
    expect(Object.keys(policies.pages)).toEqual(["privacy", "terms", "editorial", "corrections"]);
    expect(JSON.stringify(policies.pages)).toContain("must be supplied before launch");
    expect(JSON.stringify(policies.pages)).not.toContain("We do not sell");
  });

  it("publishes the exact version only after the operator supplies approved details", () => {
    const policies = publicationPolicies(configured);
    expect(policies.approved).toBe(true);
    expect(policies.publisher).toBe("The Local Publisher");
    expect(policies.effectiveDate).toBe("2026-09-18");
    expect(JSON.stringify(policies.pages)).toContain(configured.PUBLICATION_CONTACT_EMAIL);
    expect(JSON.stringify(policies.pages)).toContain(configured.PUBLICATION_RETENTION_SUMMARY);
  });

  it.each(Object.keys(configured).filter((key) => key !== "PUBLICATION_POLICY_APPROVED_VERSION"))(
    "rejects approval without %s", (key) => {
      expect(() => publicationPolicies({ ...configured, [key]: "" })).toThrow("Approved publication policies require");
    }
  );

  it("rejects stale approval versions, example contacts, invalid dates and header-like email input", () => {
    expect(() => publicationPolicies({ ...configured, PUBLICATION_POLICY_APPROVED_VERSION: "older" })).toThrow("current policy template");
    expect(() => publicationPolicies({ ...configured, PUBLICATION_CONTACT_EMAIL: "editor@example.invalid" })).toThrow("real publisher");
    expect(() => publicationPolicies({ ...configured, PUBLICATION_POLICY_EFFECTIVE_DATE: "2026-02-30" })).toThrow("valid YYYY-MM-DD");
    expect(() => publicationPolicies({ PUBLICATION_CONTACT_EMAIL: "editor@publication.test\r\nbcc:other@publication.test" })).toThrow("valid contact email");
  });
});
