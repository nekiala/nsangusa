import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { identityApi } from "@/lib/identity-api";
import { PasswordResetCompletion, VerifyEmail } from "./token-completion";

afterEach(() => vi.restoreAllMocks());
const token = "a".repeat(43);

describe("account token completion", () => {
  it("does not mutate on navigation and verifies only after explicit submission", async () => {
    const verify = vi.spyOn(identityApi, "verifyEmail").mockResolvedValue(undefined);
    render(<VerifyEmail token={token} />);
    expect(verify).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Verify email address" }));
    await waitFor(() => expect(verify).toHaveBeenCalledWith(token));
    expect(await screen.findByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/sign-in");
    expect(screen.queryByRole("button", { name: "Verify email address" })).not.toBeInTheDocument();
  });

  it("surfaces consumed or expired token errors and offers a non-enumerating resend form", async () => {
    vi.spyOn(identityApi, "verifyEmail").mockRejectedValue(new ApiError(400, { title: "Bad Request", status: 400, detail: "Token is invalid or expired" }));
    const resend = vi.spyOn(identityApi, "requestVerification").mockResolvedValue(undefined);
    render(<VerifyEmail token={token} />);
    fireEvent.click(screen.getByRole("button", { name: "Verify email address" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Token is invalid or expired");
    fireEvent.change(screen.getByLabelText("Email address"), { target: { value: "reader@example.test" } });
    fireEvent.click(screen.getByRole("button", { name: "Send verification link" }));
    await waitFor(() => expect(resend).toHaveBeenCalledWith("reader@example.test"));
    expect(await screen.findByText(/If the address needs verification/)).toBeInTheDocument();
  });

  it("checks password confirmation and submits bounded new credentials without automatic consumption", async () => {
    const reset = vi.spyOn(identityApi, "confirmPasswordReset").mockResolvedValue(undefined);
    render(<PasswordResetCompletion token={token} />);
    expect(reset).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText("New password"), { target: { value: "a-new-safe-password" } });
    fireEvent.change(screen.getByLabelText("Confirm new password"), { target: { value: "mismatched-password" } });
    fireEvent.click(screen.getByRole("button", { name: "Reset password" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("do not match");
    expect(reset).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText("Confirm new password"), { target: { value: "a-new-safe-password" } });
    fireEvent.click(screen.getByRole("button", { name: "Reset password" }));
    await waitFor(() => expect(reset).toHaveBeenCalledWith(token, "a-new-safe-password"));
    expect(await screen.findByRole("link", { name: "Sign in with your new password" })).toBeInTheDocument();
    expect(screen.queryByLabelText("New password")).not.toBeInTheDocument();
  });

  it("missing links cannot submit and use the actual recovery route", () => {
    render(<PasswordResetCompletion token="" />);
    expect(screen.getByRole("button", { name: "Reset password" })).toBeDisabled();
    expect(screen.getByRole("link", { name: "Request a new reset link" })).toHaveAttribute("href", "/password-reset");
  });
});
