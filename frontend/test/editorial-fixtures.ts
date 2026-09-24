import type { ApiArticle, SourcePost, XAccount } from "@/lib/api";

export const sources: SourcePost[] = [1, 2].map((number) => ({
  id: `00000000-0000-4000-8000-00000000000${number}`, accountId: "official-library", monitoredAccountId: "account-1",
  postId: `post-${number}`, handle: "library", canonicalUrl: `https://x.com/library/status/${number}`,
  permittedText: `Library source ${number}`, status: "active", reconciliationState: "not_required",
  publishedAt: "2026-09-03T08:00:00Z", ingestedAt: "2026-09-03T08:01:00Z", complianceAction: null,
  complianceReason: null, complianceError: null, excludedAt: null, contentDeletedAt: null, associations: [], relationships: [], version: 1
}));
export const article: ApiArticle = {
  id: "article-1", slug: "tested-workflow", headline: "Tested workflow", summary: "A deterministic summary.",
  body: "A complete article body.", editorialContext: "Background for readers.", topic: "Ideas", tags: ["Workflow"],
  state: "AWAITING_REVIEW", generatedImage: false, commentsEnabled: true, publishedAt: null,
  updatedAt: "2026-09-03T08:00:00Z", version: 1, warnings: ["Verify the times."], confidence: 0.87,
  storyCandidateId: null, seoTitle: "Distinct SEO title", seoDescription: "Distinct search description",
  approvedImageGenerationId: null, pendingImageGenerationId: null, imageApprovalRequired: false,
  correctionNote: null, approvedBy: null, approvedAt: null,
  sources: sources.map((source) => ({ sourcePostId: source.id, account: source.handle, postId: source.postId, url: source.canonicalUrl, publishedAt: source.publishedAt }))
};
export const account: XAccount = {
  id: "account-1", accountId: "official-library", handle: "library", displayName: "Library", topics: ["Ideas"],
  relevanceThreshold: 0.5, monitoringEnabled: true, createdAt: "2026-09-03T08:00:00Z", removedAt: null,
  lastSuccessfulSyncAt: null, lastSyncAttemptAt: null, rateLimitResetAt: null, rateLimitLimit: 100,
  rateLimitRemaining: 99, lastPostId: null, lastError: null, consecutiveErrors: 0, syncHealth: "healthy", version: 1
};
