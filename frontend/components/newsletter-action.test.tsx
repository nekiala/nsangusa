import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "@/lib/api";
import { NewsletterAction } from "./newsletter-action";

const id = "00000000-0000-4000-8000-000000000001";
afterEach(() => vi.restoreAllMocks());

describe("Newsletter landing pages", () => {
  it("does not confirm or unsubscribe while merely viewing the link", async () => {
    const confirm = vi.spyOn(api.newsletter, "confirm").mockResolvedValue();
    const unsubscribe = vi.spyOn(api.newsletter, "unsubscribe").mockResolvedValue();
    render(<NewsletterAction kind="confirm" id={id} token="private-token" />);
    expect(confirm).not.toHaveBeenCalled(); expect(unsubscribe).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Confirm subscription" }));
    await waitFor(() => expect(confirm).toHaveBeenCalledWith(id, "private-token"));
    expect(await screen.findByRole("status")).toHaveTextContent("confirmed");
    expect(window.location.search).toBe("");
  });

  it("requires an explicit unsubscribe action and reports expiration safely", async () => {
    const unsubscribe = vi.spyOn(api.newsletter, "unsubscribe").mockRejectedValue(new ApiError(410, { title: "Expired", status: 410, detail: "Expired token." }));
    render(<NewsletterAction kind="unsubscribe" id={id} token="private-token" />);
    expect(unsubscribe).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Unsubscribe" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("invalid or has expired");
    expect(screen.queryByText(/private-token/)).not.toBeInTheDocument();
  });

  it("disables incomplete token links", () => {
    render(<NewsletterAction kind="unsubscribe" />);
    expect(screen.getByRole("button", { name: "Unsubscribe" })).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("incomplete or invalid");
  });
});
