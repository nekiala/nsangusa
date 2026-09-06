import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, api } from "@/lib/api";
import { AuthForm, EmailForm } from "./forms";

const navigation = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: navigation.replace }) }));

afterEach(() => {
  vi.restoreAllMocks();
  navigation.replace.mockReset();
});

describe("EmailForm", () => {
  it("submits an accessible subscription request", async () => {
    const subscribe = vi.spyOn(api.newsletter, "subscribe").mockResolvedValue({ subscriptionId: "id", status: "verification_required" });
    render(<EmailForm />);
    fireEvent.change(screen.getByLabelText("Email address"), { target: { value: "reader@example.com" } });
    fireEvent.click(screen.getByRole("button", { name: "Subscribe" }));
    await waitFor(() => expect(subscribe).toHaveBeenCalledWith("reader@example.com"));
    expect(screen.getByRole("status")).toHaveTextContent("Check your inbox");
  });
});

describe("AuthForm", () => {
  it("uses the session login client and announces success", async () => {
    const login = vi.spyOn(api.auth, "login").mockResolvedValue();
    render(<AuthForm kind="sign-in" />);
    fireEvent.change(screen.getByLabelText("Email address"), { target: { value: "editor@example.com" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "a-secure-password" } });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(login).toHaveBeenCalledWith("editor@example.com", "a-secure-password"));
    expect(screen.getByRole("status")).toHaveTextContent("You are signed in.");
    expect(navigation.replace).toHaveBeenCalledWith("/profile");
  });

  it("returns to a safe requested path after authentication", async () => {
    vi.spyOn(api.auth, "login").mockResolvedValue();
    render(<AuthForm kind="sign-in" nextPath="/admin/editor" />);
    fireEvent.change(screen.getByLabelText("Email address"), { target: { value: "editor@example.com" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "a-secure-password" } });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/admin/editor"));
  });

  it("announces authentication failures as errors", async () => {
    vi.spyOn(api.auth, "login").mockRejectedValue(new ApiError(401, { title: "Unauthorized", status: 401, detail: "Email or password is incorrect." }));
    render(<AuthForm kind="sign-in" />);
    fireEvent.change(screen.getByLabelText("Email address"), { target: { value: "reader@example.com" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "wrong-password" } });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Email or password is incorrect.");
  });
});
