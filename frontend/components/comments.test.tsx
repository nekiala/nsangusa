import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, api } from "@/lib/api";
import { Comments } from "./comments";

afterEach(() => vi.restoreAllMocks());

describe("Comments", () => {
  it("submits comments for moderation and clears the form", async () => {
    const submit = vi.spyOn(api.articles, "submitComment").mockResolvedValue({ id: "comment-1" });
    render(<Comments articleId="article-1" initialComments={[]} />);
    const field = screen.getByLabelText("Add a comment");
    fireEvent.change(field, { target: { value: "A considered response." } });
    fireEvent.click(screen.getByRole("button", { name: "Submit for moderation" }));
    await waitFor(() => expect(submit).toHaveBeenCalledWith("article-1", "A considered response."));
    expect(field).toHaveValue("");
    expect(screen.getByRole("status")).toHaveTextContent("submitted for moderation");
  });

  it("announces API failures as errors", async () => {
    vi.spyOn(api.articles, "submitComment").mockRejectedValue(new ApiError(401, { title: "Unauthorized", status: 401, detail: "Sign in to comment." }));
    render(<Comments articleId="article-1" initialComments={[]} />);
    fireEvent.change(screen.getByLabelText("Add a comment"), { target: { value: "A response." } });
    fireEvent.click(screen.getByRole("button", { name: "Submit for moderation" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Sign in to comment.");
  });
});
