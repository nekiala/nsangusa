import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "@/lib/api";
import { identityApi, type AdminUser } from "@/lib/identity-api";
import { UsersWorkspace } from "./users";

const target: AdminUser = { id: "reader-1", email: "reader@example.test", displayName: "Reader", roles: ["READER"], emailVerified: true, enabled: true, deletedAt: null, createdAt: "2026-09-01T12:00:00Z", lastLoginAt: null, version: 4, rolesManagedLocally: false };
beforeEach(() => {
  vi.spyOn(api.auth, "me").mockResolvedValue({ id: "admin-1", email: "admin@example.test", displayName: "Administrator", roles: ["ADMINISTRATOR"] });
  vi.spyOn(identityApi, "users").mockResolvedValue({ items: [target], page: 0, size: 1, total: 2 });
  vi.spyOn(identityApi, "user").mockResolvedValue(target);
});
afterEach(() => vi.restoreAllMocks());

describe("user administration workspace", () => {
  it("searches and pages users without manual identifiers", async () => {
    render(<UsersWorkspace />);
    await screen.findByRole("button", { name: "Manage roles for Reader" });
    fireEvent.click(screen.getByRole("button", { name: "Next page" }));
    await waitFor(() => expect(identityApi.users).toHaveBeenLastCalledWith({ q: "", role: undefined, status: undefined, page: 1 }));
    fireEvent.change(screen.getByLabelText("Filter by role"), { target: { value: "EDITOR" } });
    await waitFor(() => expect(identityApi.users).toHaveBeenLastCalledWith({ q: "", role: "EDITOR", status: undefined, page: 0 }));
    fireEvent.change(screen.getByLabelText("Search users by name or email"), { target: { value: "reader@" } });
    fireEvent.click(screen.getByRole("button", { name: "Search users" }));
    await waitFor(() => expect(identityApi.users).toHaveBeenLastCalledWith({ q: "reader@", role: "EDITOR", status: undefined, page: 0 }));
  });

  it("requires confirmation and uses a freshly loaded user version", async () => {
    const change = vi.spyOn(identityApi, "changeRoles").mockResolvedValue(undefined);
    render(<UsersWorkspace />);
    fireEvent.click(await screen.findByRole("button", { name: "Manage roles for Reader" }));
    fireEvent.click(await screen.findByRole("checkbox", { name: "EDITOR" }));
    expect(screen.getByRole("button", { name: "Save roles" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Type reader@example.test to confirm"), { target: { value: "reader@example.test" } });
    fireEvent.click(screen.getByRole("button", { name: "Save roles" }));
    await waitFor(() => expect(change).toHaveBeenCalledWith("reader-1", ["READER", "EDITOR"], 4, "reader@example.test"));
    expect(await screen.findByText("Roles updated. Existing sessions are revoked.")).toBeInTheDocument();
  });

  it("shows conflict errors and a refresh control rather than claiming success", async () => {
    vi.spyOn(identityApi, "changeRoles").mockRejectedValue(new ApiError(409, { title: "Conflict", status: 409, detail: "User version changed" }));
    render(<UsersWorkspace />);
    fireEvent.click(await screen.findByRole("button", { name: "Manage roles for Reader" }));
    fireEvent.change(await screen.findByLabelText("Type reader@example.test to confirm"), { target: { value: "reader@example.test" } });
    fireEvent.click(screen.getByRole("button", { name: "Save roles" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("User version changed");
    expect(screen.getByRole("button", { name: "Refresh selected user" })).toBeInTheDocument();
    expect(screen.queryByText("Roles updated. Existing sessions are revoked.")).not.toBeInTheDocument();
  });

  it("does not expose role mutation controls to editors or the current administrator", async () => {
    vi.mocked(api.auth.me).mockResolvedValue({ id: "editor", email: "editor@example.test", displayName: "Editor", roles: ["EDITOR"] });
    const view = render(<UsersWorkspace />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Administrator access is required");
    expect(identityApi.users).not.toHaveBeenCalled();
    view.unmount();
    vi.mocked(api.auth.me).mockResolvedValue({ id: target.id, email: target.email, displayName: target.displayName, roles: ["ADMINISTRATOR"] });
    render(<UsersWorkspace />);
    fireEvent.click(await screen.findByRole("button", { name: "Manage roles for Reader" }));
    expect(await screen.findByText(/You cannot change your own roles/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save roles" })).not.toBeInTheDocument();
  });
});
