import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { identityApi, type AccountProfile } from "@/lib/identity-api";
import { AccountSettings } from "./account-settings";

const { replace, refresh } = vi.hoisted(() => ({ replace: vi.fn(), refresh: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace, refresh }) }));
const profile: AccountProfile = {
  id: "reader-1", email: "reader@example.test", displayName: "Current reader", roles: ["READER"],
  emailVerified: true, createdAt: "2026-09-01T12:00:00Z", lastLoginAt: null, version: 7,
  newsletter: { subscriptionId: "sub-1", status: "confirmed", frequency: "immediate" }
};
beforeEach(() => {
  replace.mockClear(); refresh.mockClear();
  vi.spyOn(identityApi, "profile").mockResolvedValue(profile);
  vi.spyOn(identityApi, "sessions").mockResolvedValue([
    { id: "session-current", createdAt: profile.createdAt, lastAccessedAt: profile.createdAt, expiresAt: profile.createdAt, current: true },
    { id: "session-other", createdAt: profile.createdAt, lastAccessedAt: profile.createdAt, expiresAt: profile.createdAt, current: false }
  ]);
});
afterEach(() => vi.restoreAllMocks());

describe("account settings", () => {
  it("loads current account and saves newsletter preference with its optimistic version", async () => {
    const update = vi.spyOn(identityApi, "updateProfile").mockResolvedValue({ ...profile, version: 8 });
    render(<AccountSettings />);
    expect(await screen.findByLabelText("Display name")).toHaveValue("Current reader");
    expect(screen.getByLabelText("Newsletter frequency")).toHaveValue("immediate");
    fireEvent.change(screen.getByLabelText("Display name"), { target: { value: "Updated reader" } });
    fireEvent.change(screen.getByLabelText("Newsletter frequency"), { target: { value: "weekly" } });
    fireEvent.click(screen.getByRole("button", { name: "Save profile" }));
    await waitFor(() => expect(update).toHaveBeenCalledWith({ displayName: "Updated reader", newsletterFrequency: "weekly", expectedVersion: 7 }));
    expect(await screen.findByText("Profile saved.")).toBeInTheDocument();
  });

  it("surfaces profile conflicts and never claims success", async () => {
    vi.spyOn(identityApi, "updateProfile").mockRejectedValue(new ApiError(409, { title: "Conflict", detail: "Refresh and retry", status: 409 }));
    render(<AccountSettings />);
    fireEvent.click(await screen.findByRole("button", { name: "Save profile" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Refresh and retry");
    expect(screen.queryByText("Profile saved.")).not.toBeInTheDocument();
  });

  it("preserves legacy every-article newsletter frequency when editing a profile", async () => {
    vi.mocked(identityApi.profile).mockResolvedValue({
      ...profile, newsletter: { ...profile.newsletter!, frequency: "all" }
    });
    render(<AccountSettings />);
    expect(await screen.findByLabelText("Newsletter frequency")).toHaveValue("immediate");
  });

  it("revokes selected sessions and redirects after revoking the current session", async () => {
    const revoke = vi.spyOn(identityApi, "revokeSession").mockResolvedValue(undefined);
    render(<AccountSettings />);
    fireEvent.click(await screen.findByRole("button", { name: "Revoke session" }));
    await waitFor(() => expect(revoke).toHaveBeenCalledWith("session-other"));
    fireEvent.click(await screen.findByRole("button", { name: "Sign out this session" }));
    await waitFor(() => expect(revoke).toHaveBeenCalledWith("session-current"));
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/sign-in"));
  });

  it("downloads actual export JSON and only enables deletion with explicit confirmation", async () => {
    const data = { generatedAt: profile.createdAt, profile, externalIdentities: [] };
    const exportData = vi.spyOn(identityApi, "exportData").mockResolvedValue(data);
    const createUrl = vi.fn().mockReturnValue("blob:account-data");
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: createUrl });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: vi.fn() });
    const download = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    const remove = vi.spyOn(identityApi, "deleteAccount").mockResolvedValue(undefined);
    render(<AccountSettings />);
    fireEvent.click(await screen.findByRole("button", { name: "Download account data" }));
    await waitFor(() => expect(exportData).toHaveBeenCalled());
    await waitFor(() => expect(download).toHaveBeenCalled());
    expect(createUrl.mock.calls[0][0]).toBeInstanceOf(Blob);
    const button = screen.getByRole("button", { name: "Permanently delete account" });
    expect(button).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Type DELETE to confirm account deletion"), { target: { value: "DELETE" } });
    await waitFor(() => expect(button).toBeEnabled());
    fireEvent.click(button);
    await waitFor(() => expect(remove).toHaveBeenCalledWith("DELETE", 7));
    expect(replace).toHaveBeenCalledWith("/sign-in?deleted=1");
  });

  it("does not conceal unavailable session storage as an empty successful session list", async () => {
    vi.mocked(identityApi.sessions).mockRejectedValue(new Error("Session management unavailable"));
    render(<AccountSettings />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Session management unavailable");
    expect(screen.queryByText("No active sessions were found.")).not.toBeInTheDocument();
  });
});
