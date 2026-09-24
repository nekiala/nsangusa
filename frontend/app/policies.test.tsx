import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import UtilityPage from "./[page]/page";
import { policyVersion } from "@/lib/publication-policies";

vi.mock("@/components/authenticated-area", () => ({ AuthenticatedArea: () => null }));
vi.mock("@/components/forms", () => ({ AuthForm: () => null }));
vi.mock("@/components/account/account-settings", () => ({ AccountSettings: () => null }));
vi.mock("@/components/account/token-completion", () => ({ PasswordResetCompletion: () => null, VerifyEmail: () => null }));
afterEach(() => vi.unstubAllEnvs());

describe("public policies", () => {
  it("keeps unapproved copy visibly draft with a usable corrections procedure", async () => {
    vi.stubEnv("PUBLICATION_POLICY_APPROVED_VERSION", "");
    render(await UtilityPage({ params: Promise.resolve({ page: "corrections" }), searchParams: Promise.resolve({}) }));
    expect(screen.getByText(/Draft publication policy/)).toBeVisible();
    expect(screen.getByText(/Send|publisher must approve and configure its public corrections contact/)).toBeVisible();
    expect(screen.getByText(/cannot retract copies already downloaded/)).toBeVisible();
  });

  it("renders operator-approved details as escaped text and records the approved version", async () => {
    vi.stubEnv("PUBLICATION_PUBLISHER", "Publisher <b>literal</b>");
    vi.stubEnv("PUBLICATION_CONTACT_EMAIL", "editor@publication.test");
    vi.stubEnv("PUBLICATION_PRIVACY_EMAIL", "privacy@publication.test");
    vi.stubEnv("PUBLICATION_JURISDICTION", "Approved jurisdiction");
    vi.stubEnv("PUBLICATION_RETENTION_SUMMARY", "Approved retention schedule.");
    vi.stubEnv("PUBLICATION_POLICY_EFFECTIVE_DATE", "2026-09-18");
    vi.stubEnv("PUBLICATION_POLICY_APPROVED_VERSION", policyVersion);
    const { container } = render(await UtilityPage({ params: Promise.resolve({ page: "privacy" }), searchParams: Promise.resolve({}) }));
    expect(screen.queryByText(/Draft publication policy/)).toBeNull();
    expect(screen.getByText("Publisher <b>literal</b>")).toBeVisible();
    expect(container.querySelector("b")).toBeNull();
    expect(screen.getByText(/Policy version: 2026-09-v1/)).toBeVisible();
    expect(screen.getByText("Approved retention schedule.")).toBeVisible();
  });
});
