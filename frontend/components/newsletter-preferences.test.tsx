import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { newsletterApi } from "@/lib/newsletter-api";
import { NewsletterPreferences } from "./newsletter-preferences";

const id = "00000000-0000-4000-8000-000000000091";
afterEach(() => vi.restoreAllMocks());

describe("email-only newsletter preferences", () => {
  it("reads without mutation, removes the URL token, and requires explicit save", async () => {
    vi.spyOn(newsletterApi, "preferences").mockResolvedValue({ status: "confirmed", frequency: "weekly", expiresAt: "2099-01-01T00:00:00Z" });
    const save = vi.spyOn(newsletterApi, "updatePreferences").mockResolvedValue();
    const replace = vi.spyOn(window.history, "replaceState");
    render(<NewsletterPreferences id={id} token="private-token" />);
    expect(await screen.findByLabelText("Email frequency")).toHaveValue("weekly");
    expect(save).not.toHaveBeenCalled();
    expect(replace).toHaveBeenCalledWith(null, "", "/newsletter/preferences");
    expect(screen.queryByText(/private-token/)).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Email frequency"), { target: { value: "daily" } });
    fireEvent.click(screen.getByRole("button", { name: "Save newsletter preferences" }));
    await waitFor(() => expect(save).toHaveBeenCalledWith(id, "private-token", "daily"));
    expect(await screen.findByRole("status")).toHaveTextContent("frequency updated");
  });

  it("requests a private link without claiming verification or delivery", async () => {
    const request = vi.spyOn(newsletterApi, "requestLink").mockResolvedValue({ message: "If this address has a confirmed subscription, a preference link will be emailed." });
    render(<NewsletterPreferences />);
    fireEvent.change(screen.getByLabelText("Email address"), { target: { value: "reader@example.test" } });
    fireEvent.click(screen.getByRole("button", { name: "Email a preference link" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith("reader@example.test"));
    expect(await screen.findByRole("status")).toHaveTextContent("If this address");
    expect(screen.getByLabelText("Email address")).toHaveValue("");
  });

  it("does not manufacture preferences or success after an expired link or transport error", async () => {
    vi.spyOn(newsletterApi, "preferences").mockRejectedValue(new ApiError(410, { status: 410, title: "Gone", detail: "Expired." }));
    vi.spyOn(newsletterApi, "requestLink").mockRejectedValue(new Error("Network unavailable"));
    render(<NewsletterPreferences id={id} token="expired-token" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("invalid or expired");
    expect(screen.queryByRole("button", { name: "Save newsletter preferences" })).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Email address"), { target: { value: "reader@example.test" } });
    fireEvent.click(screen.getByRole("button", { name: "Email a preference link" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("could not be recorded");
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });
});
