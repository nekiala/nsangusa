import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "@/lib/api";
import { AnalysisReview } from "./analysis";

afterEach(() => vi.restoreAllMocks());

describe("AI result availability", () => {
  it("distinguishes redacted results from pending work while retaining provenance", async () => {
    vi.spyOn(api.admin, "aiRequests").mockResolvedValue([{
      id: "request-1", storyCandidateId: "candidate-1", operation: "analysis", provider: "fake", model: "test-model",
      promptVersion: "analysis-v1", status: "redacted", errorCode: null, createdAt: "2026-09-01T00:00:00Z",
      completedAt: "2026-09-01T00:00:01Z", inputTokens: 10, outputTokens: 20, result: null
    }]);
    render(<AnalysisReview candidateId="candidate-1" />);
    expect(await screen.findByText("Result redacted because source content or eligibility changed. Request provenance is retained.")).toBeInTheDocument();
    expect(screen.getByText("fake / test-model")).toBeInTheDocument();
    expect(screen.queryByText("No result yet.")).not.toBeInTheDocument();
  });
});
