import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, api, type UserProfile } from "@/lib/api";
import { notifySessionChanged } from "@/lib/session-events";
import { deferred } from "@/test/deferred";
import { SiteHeader } from "./site-header";
import { AuthenticatedArea } from "./authenticated-area";

const navigation = vi.hoisted(() => ({ pathname: "/", replace: vi.fn(), refresh: vi.fn() }));
vi.mock("next/navigation", () => ({
  usePathname: () => navigation.pathname,
  useRouter: () => ({ replace: navigation.replace, refresh: navigation.refresh })
}));
const administrator: UserProfile = { id: "admin", email: "admin@example.test", displayName: "Administrator", roles: ["ADMINISTRATOR"] };
const signedOut = () => new ApiError(401, { title: "Unauthorized", status: 401, detail: "Sign in." });
beforeEach(() => { navigation.pathname = "/"; });
afterEach(() => { vi.restoreAllMocks(); navigation.replace.mockReset(); navigation.refresh.mockReset(); });

describe("SiteHeader", () => {
  it("provides a labeled primary navigation", async () => {
    vi.spyOn(api.auth, "me").mockRejectedValue(signedOut());
    render(<SiteHeader />);
    expect(screen.getByRole("navigation", { name: "Primary navigation" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Latest" })).toHaveAttribute("href", "/latest");
    await screen.findByRole("link", { name: "Sign in" });
  });

  it("does not show a sign-in link until the session is known to be anonymous", async () => {
    const pending = deferred<UserProfile>();
    vi.spyOn(api.auth, "me").mockReturnValue(pending.promise);
    render(<SiteHeader />);
    expect(screen.getByRole("status")).toHaveTextContent("Checking sign-in");
    expect(screen.queryByRole("link", { name: "Sign in" })).not.toBeInTheDocument();
    await act(async () => pending.reject(signedOut()));
    expect(await screen.findByRole("link", { name: "Sign in" })).toBeVisible();
    expect(screen.queryByRole("navigation", { name: "Your workspaces" })).not.toBeInTheDocument();
  });

  it.each([
    { roles: ["READER"], links: [] },
    { roles: ["MODERATOR"], links: ["Moderation"] },
    { roles: ["EDITOR"], links: ["Editor"] },
    { roles: ["ADMINISTRATOR"], links: ["Administration", "Editor", "Moderation"] },
    { roles: ["READER", "EDITOR", "MODERATOR"], links: ["Editor", "Moderation"] }
  ])("shows only permitted workspace entry points for $roles", async ({ roles, links }) => {
    vi.spyOn(api.auth, "me").mockResolvedValue({ ...administrator, roles });
    render(<SiteHeader />);
    expect(await screen.findByRole("link", { name: "Your account: Administrator" })).toBeVisible();
    expect(screen.queryByRole("link", { name: "Sign in" })).not.toBeInTheDocument();
    const workspaces = screen.queryByRole("navigation", { name: "Your workspaces" });
    if (!links.length) expect(workspaces).not.toBeInTheDocument();
    else expect(within(workspaces!).getAllByRole("link").map((link) => link.textContent)).toEqual(links);
  });

  it("updates the retained header after login and clears it after logout", async () => {
    const me = vi.spyOn(api.auth, "me").mockRejectedValue(signedOut());
    render(<SiteHeader />);
    await screen.findByRole("link", { name: "Sign in" });
    me.mockResolvedValue(administrator);
    act(() => notifySessionChanged());
    expect(await screen.findByRole("link", { name: "Administration" })).toHaveAttribute("href", "/admin");
    vi.spyOn(api.auth, "logout").mockImplementation(async () => {
      me.mockRejectedValue(signedOut());
      notifySessionChanged(true, true);
    });
    fireEvent.click(screen.getByRole("button", { name: "Sign out of your account" }));
    expect(await screen.findByRole("link", { name: "Sign in" })).toBeVisible();
    expect(screen.queryByRole("navigation", { name: "Your workspaces" })).not.toBeInTheDocument();
    expect(navigation.replace).toHaveBeenCalledWith("/sign-in");
    expect(navigation.refresh).toHaveBeenCalled();
  });

  it("retains the account and surfaces a failed logout without claiming success", async () => {
    vi.spyOn(api.auth, "me").mockResolvedValue(administrator);
    vi.spyOn(api.auth, "logout").mockRejectedValue(new Error("Sign-out service unavailable"));
    render(<SiteHeader />);
    fireEvent.click(await screen.findByRole("button", { name: "Sign out of your account" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Sign-out service unavailable");
    expect(screen.getByRole("link", { name: "Administration" })).toBeVisible();
    expect(navigation.replace).not.toHaveBeenCalled();
  });

  it("distinguishes a session-service failure from being signed out and allows retry", async () => {
    const me = vi.spyOn(api.auth, "me").mockRejectedValue(new Error("Offline"));
    render(<SiteHeader />);
    expect(await screen.findByRole("button", { name: "Retry account" })).toBeVisible();
    expect(screen.queryByRole("link", { name: "Sign in" })).not.toBeInTheDocument();
    me.mockResolvedValue(administrator);
    fireEvent.click(screen.getByRole("button", { name: "Retry account" }));
    expect(await screen.findByRole("link", { name: "Administration" })).toBeVisible();
  });

  it("rechecks routes and restored pages, keeping the header and protected area consistent", async () => {
    const me = vi.spyOn(api.auth, "me").mockResolvedValue(administrator);
    const { rerender } = render(<><SiteHeader /><AuthenticatedArea staff><h1>Private workspace</h1></AuthenticatedArea></>);
    await screen.findByRole("heading", { name: "Private workspace" });
    navigation.pathname = "/admin/editor";
    me.mockResolvedValue({ ...administrator, roles: ["READER"] });
    rerender(<><SiteHeader /><AuthenticatedArea staff><h1>Private workspace</h1></AuthenticatedArea></>);
    expect(screen.queryByRole("heading", { name: "Private workspace" })).not.toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Staff access is required." })).toBeVisible();
    expect(screen.queryByRole("navigation", { name: "Your workspaces" })).not.toBeInTheDocument();
    me.mockRejectedValue(signedOut());
    act(() => window.dispatchEvent(new PageTransitionEvent("pageshow", { persisted: true })));
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/sign-in?next=%2Fadmin%2Feditor"));
    expect(await screen.findByRole("link", { name: "Sign in" })).toBeVisible();
  });

  it("does not destroy unsaved fields on tab focus or a successful profile update", async () => {
    const me = vi.spyOn(api.auth, "me").mockResolvedValue(administrator);
    render(<><SiteHeader /><AuthenticatedArea><input aria-label="Draft" defaultValue="Original" /></AuthenticatedArea></>);
    const input = await screen.findByLabelText("Draft");
    fireEvent.change(input, { target: { value: "Unsaved work" } });
    act(() => window.dispatchEvent(new Event("focus")));
    await waitFor(() => expect(me).toHaveBeenCalledTimes(4));
    expect(screen.getByLabelText("Draft")).toBe(input);
    me.mockResolvedValue({ ...administrator, displayName: "Updated administrator" });
    act(() => notifySessionChanged(false));
    expect(await screen.findByRole("link", { name: "Your account: Updated administrator" })).toBeVisible();
    expect(screen.getByLabelText("Draft")).toHaveValue("Unsaved work");
    expect(screen.getByLabelText("Draft")).toBe(input);
  });

  it("ignores an in-flight account response after a session change", async () => {
    const stale = deferred<UserProfile>();
    vi.spyOn(api.auth, "me").mockReturnValueOnce(stale.promise).mockRejectedValue(signedOut());
    render(<SiteHeader />);
    act(() => notifySessionChanged());
    await screen.findByRole("link", { name: "Sign in" });
    await act(async () => stale.resolve(administrator));
    expect(screen.getByRole("link", { name: "Sign in" })).toBeVisible();
    expect(screen.queryByRole("link", { name: "Administration" })).not.toBeInTheDocument();
  });

  it("removes protected content without overwriting an explicit account-action destination", async () => {
    const me = vi.spyOn(api.auth, "me").mockResolvedValue(administrator);
    render(<><SiteHeader /><AuthenticatedArea><h1>Private account</h1></AuthenticatedArea></>);
    await screen.findByRole("heading", { name: "Private account" });
    me.mockRejectedValue(signedOut());
    act(() => notifySessionChanged(true, true));
    await screen.findByRole("heading", { name: "You are signed out." });
    expect(screen.queryByRole("heading", { name: "Private account" })).not.toBeInTheDocument();
    act(() => notifySessionChanged());
    await screen.findByRole("heading", { name: "You are signed out." });
    expect(navigation.replace).not.toHaveBeenCalled();
  });
});
