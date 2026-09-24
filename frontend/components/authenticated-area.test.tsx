import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, api } from "@/lib/api";
import { AuthenticatedArea } from "./authenticated-area";

const navigation = vi.hoisted(() => ({ replace: vi.fn(), pathname: "/admin/editor" }));
vi.mock("next/navigation", () => ({
  usePathname: () => navigation.pathname,
  useRouter: () => ({ replace: navigation.replace })
}));

afterEach(() => {
  vi.restoreAllMocks();
  navigation.replace.mockReset();
});

describe("AuthenticatedArea", () => {
  it("renders protected content for staff", async () => {
    vi.spyOn(api.auth, "me").mockResolvedValue({ id: "1", email: "editor@example.com", displayName: "Editor", roles: ["EDITOR"] });
    render(<AuthenticatedArea staff><h1>Article editor</h1></AuthenticatedArea>);
    expect(await screen.findByRole("heading", { name: "Article editor" })).toBeInTheDocument();
  });

  it("redirects unauthenticated visitors back to the requested path", async () => {
    vi.spyOn(api.auth, "me").mockRejectedValue(new ApiError(401, { title: "Unauthorized", status: 401, detail: "Sign in." }));
    render(<AuthenticatedArea staff><h1>Article editor</h1></AuthenticatedArea>);
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/sign-in?next=%2Fadmin%2Feditor"));
    expect(screen.queryByRole("heading", { name: "Article editor" })).not.toBeInTheDocument();
  });

  it("shows a deterministic permission error for non-staff members", async () => {
    vi.spyOn(api.auth, "me").mockResolvedValue({ id: "1", email: "reader@example.com", displayName: "Reader", roles: ["READER"] });
    render(<AuthenticatedArea staff><h1>Article editor</h1></AuthenticatedArea>);
    expect(await screen.findByRole("heading", { name: "Staff access is required." })).toBeInTheDocument();
  });

  it("admits moderators only to explicitly permitted staff sections", async () => {
    vi.spyOn(api.auth, "me").mockResolvedValue({ id: "1", email: "moderator@example.test", displayName: "Moderator", roles: ["MODERATOR"] });
    const { rerender } = render(<AuthenticatedArea allowedRoles={["MODERATOR", "ADMINISTRATOR"]}><h1>Moderation queue</h1></AuthenticatedArea>);
    expect(await screen.findByRole("heading", { name: "Moderation queue" })).toBeVisible();
    rerender(<AuthenticatedArea allowedRoles={["ADMINISTRATOR"]}><h1>User administration</h1></AuthenticatedArea>);
    expect(await screen.findByRole("heading", { name: "Staff access is required." })).toBeVisible();
    expect(screen.queryByRole("heading", { name: "User administration" })).toBeNull();
  });
});
