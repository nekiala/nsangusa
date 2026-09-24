import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { newsletterApi, type Delivery } from "@/lib/newsletter-api";
import { NewsletterWorkspace } from "./newsletter";

const date = "2026-09-10T10:00:00Z";
const delivery: Delivery = {
  id: "delivery-1", subscriptionId: "subscription-1", articleId: "article-1", campaignKey: "weekly:2026-09-10",
  status: "reconciliation_required", attemptCount: 1, failureCode: "acceptance_unknown", createdAt: date,
  providerAcceptedAt: null, deliveryConfirmedAt: null, attemptStartedAt: date, providerMessageId: null,
  providerIdempotencyApplied: false, reconciledAt: null, reconciliationOutcome: null, reconciliationEvidence: null,
};
beforeEach(() => {
  vi.spyOn(newsletterApi, "subscriptions").mockResolvedValue({ items: [{
    id: "subscription-1", status: "confirmed", frequency: "weekly", consentSource: "footer", consentAt: date,
    verifiedAt: date, unsubscribedAt: null, accountLinked: false,
  }], page: 0, size: 1, total: 2 });
});
afterEach(() => vi.restoreAllMocks());

describe("newsletter administration", () => {
  it("filters and pages subscriptions, then opens their actual consent records", async () => {
    vi.spyOn(newsletterApi, "consent").mockResolvedValue({ items: [{ id: "consent-1", action: "confirmed", source: "email-token", occurredAt: date }], page: 0, size: 20, total: 1 });
    render(<NewsletterWorkspace />);
    await screen.findByText("subscription-1");
    fireEvent.click(within(screen.getByRole("navigation", { name: "Subscription pages" })).getByRole("button", { name: "Next page" }));
    await waitFor(() => expect(newsletterApi.subscriptions).toHaveBeenLastCalledWith("", "", 1));
    fireEvent.change(screen.getByLabelText("Subscription status"), { target: { value: "suppressed" } });
    await waitFor(() => expect(newsletterApi.subscriptions).toHaveBeenLastCalledWith("suppressed", "", 0));
    fireEvent.click(screen.getByRole("button", { name: "View consent history" }));
    expect(await screen.findByRole("region", { name: "Consent history" })).toBeInTheDocument();
    await waitFor(() => expect(newsletterApi.consent).toHaveBeenCalledWith("subscription-1", 0));
  });

  it("requires evidence and explicit acknowledgement; reconciliation never resends", async () => {
    vi.spyOn(newsletterApi, "deliveries").mockResolvedValue({ items: [delivery], page: 0, size: 20, total: 1 });
    const reconcile = vi.spyOn(newsletterApi, "reconcile").mockResolvedValue();
    render(<NewsletterWorkspace />);
    fireEvent.click(screen.getByRole("button", { name: "Deliveries" }));
    const form = await screen.findByRole("form", { name: "Reconcile uncertain send" });
    expect(within(form).getByRole("button", { name: "Record reconciliation" })).toBeDisabled();
    expect(reconcile).not.toHaveBeenCalled();
    fireEvent.change(within(form).getByLabelText(/Evidence reference/), { target: { value: "ticket:123" } });
    fireEvent.click(within(form).getByRole("checkbox"));
    fireEvent.click(within(form).getByRole("button", { name: "Record reconciliation" }));
    await waitFor(() => expect(reconcile).toHaveBeenCalledWith("delivery-1", { outcome: "abandoned", evidenceReference: "ticket:123", providerMessageId: null }));
    expect(await screen.findByRole("status")).toHaveTextContent("No message was resent");
    expect(screen.queryByRole("button", { name: /^Resend/ })).not.toBeInTheDocument();
  });

  it("shows failed authorization instead of success-shaped data", async () => {
    vi.mocked(newsletterApi.subscriptions).mockRejectedValue(new ApiError(403, { status: 403, title: "Forbidden", detail: "Administrator role required." }));
    render(<NewsletterWorkspace />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Administrator role required");
    expect(screen.queryByText("subscription-1")).not.toBeInTheDocument();
    expect(screen.queryByText("No subscriptions match these filters.")).not.toBeInTheDocument();
  });
});
