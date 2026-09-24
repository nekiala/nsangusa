import { contentError, contentFromBody, contentPlainText, type ArticleContent } from "@/lib/article-content";
import { notifySessionChanged } from "@/lib/session-events";

export type Identifier = string;
export type ArticleState = "DISCOVERED" | "ANALYZING" | "DRAFTING" | "AWAITING_REVIEW" | "APPROVED" | "SCHEDULED" | "PUBLISHED" | "UNPUBLISHED" | "REJECTED" | "ARCHIVED";
export type ArticleSource = { sourcePostId: Identifier; account: string; postId: string; url: string; publishedAt: string };
export type ApiArticle = {
  id: Identifier; slug: string; headline: string; summary: string; body: string; content?: ArticleContent | null; editorialContext: string | null;
  topic: string; tags: string[]; state: ArticleState; heroObjectKey?: string | null; imageAltText?: string | null;
  generatedImage: boolean; commentsEnabled: boolean; publishedAt: string | null; updatedAt: string; version: number;
  sources: ArticleSource[]; warnings: string[]; confidence: number;
  storyCandidateId: Identifier | null; seoTitle: string; seoDescription: string;
  approvedImageGenerationId: Identifier | null; pendingImageGenerationId: Identifier | null; imageApprovalRequired: boolean;
  correctionNote: string | null; approvedBy: Identifier | null; approvedAt: string | null;
};
export type ArticleCommand = {
  headline: string; summary: string; body?: string; content?: ArticleContent | null; editorialContext?: string | null; seoTitle: string;
  seoDescription: string; slugSuggestion: string; topic: string; tags: string[]; sources: ArticleSource[]; commentsEnabled: boolean;
};
export type Comment = { id: Identifier; authorId: Identifier; body: string; parentId?: Identifier | null; state: string };
export type UserProfile = { id: Identifier; email: string; displayName: string; roles: string[] };
export type Problem = { type?: string; title: string; status: number; detail: string; instance?: string; traceId?: string; timestamp?: string };
export type OperationalSummary = { status: "available"; checkedAt: string; details: string };
export type SearchResult = {
  articleId: Identifier; slug: string; headline: string; summary: string; topic: string;
  tags: string[]; publishedAt: string; rank: number;
};
export type SearchPage = { items: SearchResult[]; page: number; size: number; total: number };
export type Page<T> = { items: T[]; page: number; size: number; total: number };
export type RevisionSnapshot = Pick<ApiArticle,
  "slug" | "headline" | "summary" | "body" | "content" | "editorialContext" | "seoTitle" | "seoDescription" |
  "topic" | "tags" | "sources" | "heroObjectKey" | "imageAltText" | "generatedImage" |
  "approvedImageGenerationId" | "pendingImageGenerationId" | "imageApprovalRequired" | "commentsEnabled" |
  "state" | "confidence" | "warnings" | "correctionNote" | "approvedBy" | "approvedAt" | "publishedAt" | "updatedAt"
> & { humanReviewRequired: boolean };
export type RevisionView = {
  id: Identifier; revisionNumber: number; reason: string; actorId: Identifier | null; createdAt: string;
  snapshot: RevisionSnapshot | null; aiGenerationResult: Record<string, unknown> | null; legacy: boolean;
  headline?: string | null; summary?: string | null; body?: string | null;
};
export type RevisionComparison = { from: RevisionView; to: RevisionView; changedFields: string[] };
export type ScheduleStatus = "scheduled" | "published" | "cancelled" | "failed";
export type PublicationSchedule = {
  id: Identifier; articleId: Identifier; publishAt: string; status: ScheduleStatus; version: number;
  articleVersion: number | null; scheduledBy: Identifier; updatedBy: Identifier; createdAt: string; updatedAt: string;
  completedAt: string | null; lastAttemptAt: string | null; attemptCount: number; lastError: string | null;
};
export type PublicationPolicy = {
  policy: string; confidenceThreshold: number; topicRules: Record<string, number>; approvedSourceAccounts: string[];
  humanPublicationAllowed: boolean; explanation: string;
};
export type StoryCandidate = { id: Identifier; topic: string; status: string; createdAt: string; sourceIds: Identifier[]; articleId: Identifier | null };
export type AiRequest = {
  id: Identifier; storyCandidateId: Identifier; operation: string; provider: string; model: string; promptVersion: string;
  status: string; errorCode: string | null; createdAt: string; completedAt: string | null;
  inputTokens: number | null; outputTokens: number | null; result: Record<string, unknown> | null;
};
export type SourceSummary = {
  id: Identifier; postId: string; accountId: string; handle: string; status: string; reconciliationState: string;
  publishedAt: string; ingestedAt: string;
};
export type SourcePost = SourceSummary & {
  monitoredAccountId: Identifier; canonicalUrl: string; permittedText: string | null; complianceAction: string | null;
  complianceReason: string | null; complianceError: string | null; excludedAt: string | null; contentDeletedAt: string | null;
  associations: { associationType: string; associatedId: Identifier; state: string }[];
  relationships: { id: Identifier; relatedPostId: string; relationshipType: string }[]; version: number;
};
export type XAccount = {
  id: Identifier; accountId: string; handle: string; displayName: string; topics: string[]; relevanceThreshold: number;
  monitoringEnabled: boolean; createdAt: string; removedAt: string | null; lastSuccessfulSyncAt: string | null;
  lastSyncAttemptAt: string | null; rateLimitResetAt: string | null; rateLimitLimit: number | null;
  rateLimitRemaining: number | null; lastPostId: string | null; lastError: string | null; consecutiveErrors: number;
  syncHealth: string; version: number;
};
export type XAccountInput = Pick<XAccount, "accountId" | "handle" | "displayName" | "topics" | "relevanceThreshold">;
export type ResolvedXAccount = { accountId: string; handle: string; displayName: string; simulated: boolean };
export type ImageGeneration = {
  id: Identifier; articleId: Identifier; prompt: string; altText: string; objectKey: string | null;
  provider: string; model: string; safetyStatus: string; createdAt: string; approvedAt: string | null;
};
export type ImageVariant = "hero" | "thumbnail" | "social";
export type NewsletterFrequency = "immediate" | "daily" | "weekly";

export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem;

  constructor(status: number, problem: Problem) {
    super(problem.detail || problem.title);
    this.name = "ApiError";
    this.status = status;
    this.problem = problem;
  }
}

type Fetcher = typeof fetch;
type WebRequestInit = NonNullable<Parameters<Fetcher>[1]>;
type ClientOptions = { baseUrl?: string; fetch?: Fetcher; mode?: "api" | "fake" };
type Csrf = { headerName: string; token: string };
const fakeId = (value: number) => `00000000-0000-4000-8000-${value.toString().padStart(12, "0")}`;
const versionQuery = (expectedVersion?: number) => expectedVersion === undefined ? "" : `?expectedVersion=${encodeURIComponent(expectedVersion)}`;

const fakeArticles: ApiArticle[] = [
  {
    id: fakeId(1), slug: "the-work-of-paying-attention", headline: "The work of paying attention",
    summary: "In a culture built for interruption, sustained looking becomes a civic practice.",
    body: "Attention is not merely a private resource. It is the medium in which common life appears: a street noticed, a claim examined, a neighbour heard before the answer arrives.\n\nThe systems around us make a different promise. They offer speed as care and volume as proof. The slow fact, the unfinished account, and the person who will not fit the category still ask to be met on their own terms.\n\nGood publication is an arrangement for that meeting. It makes room for a reader to pause, for a writer to qualify, and for evidence to remain visible long enough to be considered.",
    editorialContext: null, topic: "Ideas", tags: ["Attention", "Public life"], state: "PUBLISHED", generatedImage: false, commentsEnabled: true,
    publishedAt: "2026-08-29T09:00:00.000Z", updatedAt: "2026-08-29T09:00:00.000Z", version: 1, sources: [], warnings: [], confidence: 1
  },
  {
    id: fakeId(2), slug: "a-library-after-dark", headline: "A library after dark",
    summary: "A small public room keeps its lights on and becomes a map of the city around it.",
    body: "At seven, the return desk closes. At seven-oh-one, another institution opens: a warm, quiet commons where the day’s schedules loosen.\n\nThere are students, shift workers and readers who have come only to sit among books. Their presence is an argument for rooms that ask nothing but attention.",
    editorialContext: null, topic: "Cities", tags: ["Libraries", "Night"], state: "PUBLISHED", generatedImage: false, commentsEnabled: true,
    publishedAt: "2026-08-27T12:30:00.000Z", updatedAt: "2026-08-27T12:30:00.000Z", version: 1, sources: [], warnings: [], confidence: 1
  },
  {
    id: fakeId(3), slug: "the-tools-that-keep-a-promise", headline: "The tools that keep a promise",
    summary: "Technology is useful when its limits are legible to the people who depend on it.",
    body: "A tool makes a promise about the future. The best ones state that promise plainly, document their failure modes, and leave a human being able to intervene.\n\nThe alternative is not automation but opacity: a machine whose certainty is more persuasive than its evidence.",
    editorialContext: null, topic: "Technology", tags: ["Systems", "Trust"], state: "PUBLISHED", generatedImage: false, commentsEnabled: true,
    publishedAt: "2026-08-24T08:00:00.000Z", updatedAt: "2026-08-24T08:00:00.000Z", version: 1, sources: [], warnings: [], confidence: 1
  },
  {
    id: fakeId(4), slug: "what-a-neighborhood-hears", headline: "What a neighborhood hears",
    summary: "Listening for the ordinary soundscape of a place reveals its changing terms.",
    body: "Every neighbourhood has a daily score: shutters, buses, greetings, a delivery cart crossing a seam in the pavement.\n\nTo hear it is to understand that change is first experienced as a difference in what can be expected.",
    editorialContext: null, topic: "Culture", tags: ["Sound", "Place"], state: "PUBLISHED", generatedImage: false, commentsEnabled: true,
    publishedAt: "2026-08-19T10:15:00.000Z", updatedAt: "2026-08-19T10:15:00.000Z", version: 1, sources: [], warnings: [], confidence: 1
  }
].map((article) => ({
  ...article, state: "PUBLISHED", storyCandidateId: null, seoTitle: article.headline, seoDescription: article.summary,
  approvedImageGenerationId: null, pendingImageGenerationId: null, imageApprovalRequired: false,
  correctionNote: null, approvedBy: null, approvedAt: null
}));

function configuredMode(): "api" | "fake" {
  return process.env.NEXT_PUBLIC_API_MODE === "fake" ? "fake" : "api";
}

function apiBaseUrl() {
  if (typeof window === "undefined") return process.env.NSANGUSA_API_URL || "http://localhost:8080";
  return process.env.NEXT_PUBLIC_API_URL ?? "";
}

export function publicImageUrl(slug: string, variant: ImageVariant = "hero") {
  return `${process.env.NEXT_PUBLIC_API_URL || ""}/api/v1/articles/${encodeURIComponent(slug)}/image?variant=${variant}`;
}

function problemFrom(response: Response, value: unknown): Problem {
  if (value && typeof value === "object") {
    const candidate = value as Partial<Problem>;
    if (typeof candidate.title === "string" && typeof candidate.detail === "string") {
      return { ...candidate, status: typeof candidate.status === "number" ? candidate.status : response.status } as Problem;
    }
  }
  return { title: response.statusText || "Request failed", status: response.status, detail: "The service could not complete this request." };
}

export function createApiClient(options: ClientOptions = {}) {
  const mode = options.mode || configuredMode();
  const baseUrl = (options.baseUrl || apiBaseUrl()).replace(/\/$/, "");
  const fetcher = options.fetch || fetch;
  let csrf: Csrf | undefined;
  let currentUserRequest: Promise<UserProfile> | undefined;
  const pendingMutations = new Map<string, string>();

  function sessionChanged(reset = true, ending = false) {
    currentUserRequest = undefined;
    notifySessionChanged(reset, ending);
  }

  async function request<T>(path: string, init: WebRequestInit = {}, unsafe = false, requestKey?: string): Promise<T> {
    const headers = new Headers(init.headers);
    let fingerprint = unsafe && path.startsWith("/api/v1/admin/")
      ? JSON.stringify({ path, method: init.method, payload: typeof init.body === "string" ? canonicalPayload(JSON.parse(init.body)) : null })
      : null;
    if (fingerprint !== null && path === "/api/v1/admin/ai-configuration/setup/credential" && init.method === "PUT") {
      const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(fingerprint));
      fingerprint = `ai-credential:${Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("")}`;
    }
    if (fingerprint !== null) {
      if (!pendingMutations.has(fingerprint) && pendingMutations.size >= 100) {
        throw new Error("Too many editorial requests have unconfirmed outcomes. Reconcile them before submitting more changes.");
      }
      const key = requestKey || headers.get("Idempotency-Key") || pendingMutations.get(fingerprint) || crypto.randomUUID();
      headers.set("Idempotency-Key", key);
      pendingMutations.set(fingerprint, key);
    }
    try {
      let result: T;
      if (mode === "fake") {
        result = await fakeRequest<T>(path, { ...init, headers });
      } else {
        if (unsafe) csrf ||= await bootstrapCsrf();
        if (unsafe && csrf) headers.set(csrf.headerName, csrf.token);
        if (init.body && !headers.has("Content-Type") && !(init.body instanceof URLSearchParams)) headers.set("Content-Type", "application/json");
        const response = await fetcher(`${baseUrl}${path}`, {
          ...init, headers, credentials: "include", cache: "no-store", next: { revalidate: 0 }
        } as WebRequestInit);
        if (!response.ok) {
          let value: unknown;
          try { value = await response.json(); } catch { /* A gateway may return an empty error response. */ }
          throw new ApiError(response.status, problemFrom(response, value));
        }
        result = response.status === 204 || response.status === 205 || !response.headers.get("content-type")?.includes("application/json")
          ? undefined as T : await response.json() as T;
      }
      if (fingerprint !== null) pendingMutations.delete(fingerprint);
      if (unsafe && (path === "/api/v1/auth/logout" || path === "/api/v1/auth/me"
        || path.startsWith("/api/v1/auth/sessions/") || path === "/api/v1/auth/password-reset/confirm")) {
        sessionChanged(path === "/api/v1/auth/logout" || path === "/api/v1/auth/password-reset/confirm"
          || path === "/api/v1/auth/me" && init.method === "DELETE",
        path === "/api/v1/auth/logout" || init.method === "DELETE");
      }
      return result;
    } catch (error) {
      if ((path !== "/api/v1/auth/me" || unsafe) && error instanceof ApiError && error.status === 401) sessionChanged();
      // Transport/5xx/parse failures may follow a committed mutation; retain its retry identity.
      if (fingerprint !== null && error instanceof ApiError && error.status >= 400 && error.status < 500 && error.status !== 408) {
        pendingMutations.delete(fingerprint);
      }
      throw error;
    }
  }

  async function bootstrapCsrf() {
    if (mode === "fake") return { headerName: "X-XSRF-TOKEN", token: "fake-csrf" };
    const response = await fetcher(`${baseUrl}/api/v1/auth/csrf`, {
      credentials: "include", cache: "no-store", next: { revalidate: 0 }
    } as WebRequestInit);
    if (!response.ok) {
      let value: unknown;
      try { value = await response.json(); } catch { /* handled below */ }
      throw new ApiError(response.status, problemFrom(response, value));
    }
    return response.json() as Promise<Csrf>;
  }

  async function login(email: string, password: string) {
    if (mode === "fake") { await fakeLogin(email); pendingMutations.clear(); sessionChanged(); return; }
    csrf ||= await bootstrapCsrf();
    const headers = new Headers();
    headers.set(csrf.headerName, csrf.token);
    const response = await fetcher(`${baseUrl}/api/v1/auth/login`, {
      method: "POST", body: new URLSearchParams({ username: email, password }), redirect: "manual",
      headers, credentials: "include", cache: "no-store", next: { revalidate: 0 }
    } as WebRequestInit);
    if (response.ok || response.status === 302 || response.status === 303 || response.type === "opaqueredirect") {
      csrf = undefined;
      pendingMutations.clear();
      sessionChanged();
      return;
    }
    let value: unknown;
    try { value = await response.json(); } catch { /* handled below */ }
    throw new ApiError(response.status, problemFrom(response, value));
  }

  function currentUser() {
    // Share only simultaneous browser reads, never cache an account across server requests.
    if (typeof window === "undefined") return request<UserProfile>("/api/v1/auth/me");
    if (!currentUserRequest) {
      const pending = request<UserProfile>("/api/v1/auth/me").finally(() => {
        if (currentUserRequest === pending) currentUserRequest = undefined;
      });
      currentUserRequest = pending;
    }
    return currentUserRequest;
  }

  return {
    mode,
    request,
    bootstrapCsrf,
    auth: {
      register: (input: { email: string; password: string; displayName: string }) => request<{ status: "verification_required" }>("/api/v1/auth/register", { method: "POST", body: JSON.stringify(input) }, true),
      login,
      me: currentUser,
      logout: async () => { await request<void>("/api/v1/auth/logout", { method: "POST", redirect: "manual" }, true); csrf = undefined; pendingMutations.clear(); },
      verifyEmail: (token: string) => request<void>("/api/v1/auth/verify-email", { method: "POST", body: JSON.stringify({ token }) }, true),
      requestPasswordReset: (email: string) => request<void>("/api/v1/auth/password-reset/request", { method: "POST", body: JSON.stringify({ email }) }, true),
      confirmPasswordReset: (token: string, newPassword: string) => request<void>("/api/v1/auth/password-reset/confirm", { method: "POST", body: JSON.stringify({ token, newPassword }) }, true)
    },
    articles: {
      latest: (limit = 20) => request<ApiArticle[]>(`/api/v1/articles?limit=${encodeURIComponent(limit)}`),
      bySlug: (slug: string) => request<ApiArticle>(`/api/v1/articles/${encodeURIComponent(slug)}`),
      search: (query: string, page = 0, size = 20) => request<SearchPage>(`/api/v1/search?q=${encodeURIComponent(query)}&page=${page}&size=${size}`),
      byTopic: (topic: string, page = 0, size = 20) => request<SearchPage>(`/api/v1/topics/${encodeURIComponent(topic)}?page=${page}&size=${size}`),
      byTag: (tag: string, page = 0, size = 20) => request<SearchPage>(`/api/v1/tags/${encodeURIComponent(tag)}?page=${page}&size=${size}`),
      comments: (articleId: Identifier) => request<Comment[]>(`/api/v1/articles/${encodeURIComponent(articleId)}/comments`),
      submitComment: (articleId: Identifier, body: string, parentId?: Identifier | null) => request<{ id: Identifier }>(`/api/v1/articles/${encodeURIComponent(articleId)}/comments`, { method: "POST", body: JSON.stringify({ body, parentId: parentId || null }) }, true)
    },
    newsletter: {
      subscribe: (email: string, frequency: NewsletterFrequency | "all" = "weekly") => request<{ subscriptionId: Identifier; status: string }>("/api/v1/newsletter/subscriptions", { method: "POST", body: JSON.stringify({ email, consentSource: "website", frequency }) }, true),
      confirm: (id: Identifier, token: string) => request<void>(`/api/v1/newsletter/confirm?id=${encodeURIComponent(id)}&token=${encodeURIComponent(token)}`, { method: "POST" }, true),
      unsubscribe: (id: Identifier, token: string) => request<void>(`/api/v1/newsletter/unsubscribe?id=${encodeURIComponent(id)}&token=${encodeURIComponent(token)}`, { method: "POST" }, true)
    },
    admin: {
      articles: (state?: ArticleState, page = 0, size = 20) => request<Page<ApiArticle>>(`/api/v1/admin/articles?page=${page}&size=${size}${state ? `&state=${encodeURIComponent(state)}` : ""}`, { cache: "no-store" }),
      candidates: (page = 0, size = 20) => request<Page<StoryCandidate>>(`/api/v1/admin/story-candidates?page=${page}&size=${size}`, { cache: "no-store" }),
      regenerateCandidate: (id: Identifier, requestKey?: string) => request<{ id: Identifier }>(`/api/v1/admin/story-candidates/${encodeURIComponent(id)}/regenerate`, { method: "POST" }, true, requestKey),
      aiRequests: (storyCandidateId: Identifier) => request<AiRequest[]>(`/api/v1/admin/ai-requests?storyCandidateId=${encodeURIComponent(storyCandidateId)}`, { cache: "no-store" }),
      sources: (status = "active", accountId?: string) => request<SourceSummary[]>(`/api/v1/admin/source-posts?status=${encodeURIComponent(status)}&limit=100${accountId ? `&accountId=${encodeURIComponent(accountId)}` : ""}`, { cache: "no-store" }),
      source: (id: Identifier) => request<SourcePost>(`/api/v1/admin/source-posts/${encodeURIComponent(id)}`, { cache: "no-store" }),
      excludeSource: (id: Identifier, reason: string, requestKey?: string) => request<void>(`/api/v1/admin/source-posts/${encodeURIComponent(id)}/exclude`, { method: "POST", body: JSON.stringify({ reason }) }, true, requestKey),
      article: (id: Identifier) => request<ApiArticle>(`/api/v1/admin/articles/${encodeURIComponent(id)}`, { cache: "no-store" }),
      revisions: (id: Identifier, page = 0, size = 20) => request<Page<RevisionView>>(`/api/v1/admin/articles/${encodeURIComponent(id)}/revisions?page=${page}&size=${size}`),
      compareRevisions: (id: Identifier, from: number, to: number) => request<RevisionComparison>(`/api/v1/admin/articles/${encodeURIComponent(id)}/revisions/compare?from=${from}&to=${to}`),
      startCorrection: (id: Identifier, expectedVersion: number, note: string, requestKey?: string) => request<void>(`/api/v1/admin/articles/${encodeURIComponent(id)}/corrections`, { method: "POST", body: JSON.stringify({ expectedVersion, note }) }, true, requestKey),
      publicationSchedules: (status?: ScheduleStatus, page = 0, size = 20, articleId?: Identifier) => request<Page<PublicationSchedule>>(`/api/v1/admin/publication-schedules?page=${page}&size=${size}${status ? `&status=${encodeURIComponent(status)}` : ""}${articleId ? `&articleId=${encodeURIComponent(articleId)}` : ""}`),
      reschedulePublication: (id: Identifier, expectedVersion: number, publishAt: string, requestKey?: string) => request<PublicationSchedule>(`/api/v1/admin/publication-schedules/${encodeURIComponent(id)}`, { method: "PATCH", body: JSON.stringify({ expectedVersion, publishAt }) }, true, requestKey),
      cancelPublication: (id: Identifier, expectedVersion: number, requestKey?: string) => request<PublicationSchedule>(`/api/v1/admin/publication-schedules/${encodeURIComponent(id)}/cancel`, { method: "POST", body: JSON.stringify({ expectedVersion }) }, true, requestKey),
      publicationPolicy: () => request<PublicationPolicy>("/api/v1/admin/publication-policy"),
      images: (id: Identifier) => request<ImageGeneration[]>(`/api/v1/admin/articles/${encodeURIComponent(id)}/images`, { cache: "no-store" }),
      regenerateImage: (id: Identifier, prompt: string, altText: string, requestKey?: string) => request<{ eventId: Identifier }>(`/api/v1/admin/articles/${encodeURIComponent(id)}/images/regenerate`, { method: "POST", body: JSON.stringify({ prompt, altText }) }, true, requestKey),
      requestFallbackImage: (id: Identifier, altText: string, reason: string, expectedVersion: number, requestKey?: string) => request<{ eventId: Identifier }>(`/api/v1/admin/articles/${encodeURIComponent(id)}/images/fallback`, { method: "POST", body: JSON.stringify({ altText, reason, expectedVersion }) }, true, requestKey),
      approveImage: (id: Identifier, requestKey?: string) => request<void>(`/api/v1/admin/image-generations/${encodeURIComponent(id)}/approve`, { method: "POST" }, true, requestKey),
      imageContent: async (id: Identifier, variant: ImageVariant = "hero"): Promise<Blob> => {
        if (mode === "fake") return new Blob(['<svg xmlns="http://www.w3.org/2000/svg" width="800" height="450"><rect width="800" height="450" fill="#eae7df"/><text x="50" y="220" font-size="36">Demo editorial illustration</text></svg>'], { type: "image/svg+xml" });
        const response = await fetcher(`${baseUrl}/api/v1/admin/image-generations/${encodeURIComponent(id)}/content?variant=${variant}`, { credentials: "include", cache: "no-store" });
        if (!response.ok) {
          let value: unknown;
          try { value = await response.json(); } catch { /* Some gateways return an empty response. */ }
          throw new ApiError(response.status, problemFrom(response, value));
        }
        return response.blob();
      },
      createArticle: (input: ArticleCommand, requestKey?: string) => request<{ id: Identifier }>("/api/v1/admin/articles", { method: "POST", body: JSON.stringify(input) }, true, requestKey),
      editArticle: (id: Identifier, expectedVersion: number, input: ArticleCommand, requestKey?: string) => request<void>(`/api/v1/admin/articles/${encodeURIComponent(id)}?expectedVersion=${encodeURIComponent(expectedVersion)}`, { method: "PUT", body: JSON.stringify(input) }, true, requestKey),
      approveArticle: (id: Identifier, requestKey?: string, expectedVersion?: number) => request<void>(`/api/v1/admin/articles/${encodeURIComponent(id)}/approve${versionQuery(expectedVersion)}`, { method: "POST" }, true, requestKey),
      publishArticle: (id: Identifier, requestKey?: string, expectedVersion?: number) => request<void>(`/api/v1/admin/articles/${encodeURIComponent(id)}/publish${versionQuery(expectedVersion)}`, { method: "POST" }, true, requestKey),
      scheduleArticle: (id: Identifier, publishAt: string, requestKey?: string) => request<{ scheduleId: Identifier }>(`/api/v1/admin/articles/${encodeURIComponent(id)}/schedule`, { method: "POST", body: JSON.stringify({ publishAt }) }, true, requestKey),
      rejectArticle: (id: Identifier, requestKey?: string, expectedVersion?: number) => request<void>(`/api/v1/admin/articles/${encodeURIComponent(id)}/reject${versionQuery(expectedVersion)}`, { method: "POST" }, true, requestKey),
      unpublishArticle: (id: Identifier, requestKey?: string, expectedVersion?: number) => request<void>(`/api/v1/admin/articles/${encodeURIComponent(id)}/unpublish${versionQuery(expectedVersion)}`, { method: "POST" }, true, requestKey),
      restoreArticle: (id: Identifier, requestKey?: string, expectedVersion?: number) => request<void>(`/api/v1/admin/articles/${encodeURIComponent(id)}/restore${versionQuery(expectedVersion)}`, { method: "POST" }, true, requestKey),
      archiveArticle: (id: Identifier, requestKey?: string, expectedVersion?: number) => request<void>(`/api/v1/admin/articles/${encodeURIComponent(id)}/archive${versionQuery(expectedVersion)}`, { method: "POST" }, true, requestKey),
      xAccounts: () => request<XAccount[]>("/api/v1/admin/x-accounts", { cache: "no-store" }),
      xCapabilities: () => request<{ simulationEnabled: boolean }>("/api/v1/admin/x-accounts/capabilities", { cache: "no-store" }),
      resolveXAccount: (handle: string) => request<ResolvedXAccount>(`/api/v1/admin/x-accounts/resolve?handle=${encodeURIComponent(handle)}`, { cache: "no-store" }),
      addXAccount: (input: XAccountInput, requestKey?: string) => request<{ id: Identifier }>("/api/v1/admin/x-accounts", { method: "POST", body: JSON.stringify(input) }, true, requestKey),
      editXAccount: (id: Identifier, input: Omit<XAccountInput, "accountId" | "handle"> & { monitoringEnabled: boolean }, requestKey?: string) => request<void>(`/api/v1/admin/x-accounts/${encodeURIComponent(id)}`, { method: "PUT", body: JSON.stringify(input) }, true, requestKey),
      removeXAccount: (id: Identifier, reason: string, requestKey?: string) => request<void>(`/api/v1/admin/x-accounts/${encodeURIComponent(id)}`, { method: "DELETE", body: JSON.stringify({ reason }) }, true, requestKey),
      blockedAccounts: () => request<{ accountId: string; reason: string; createdAt: string }[]>("/api/v1/admin/blocked-source-accounts", { cache: "no-store" }),
      blockAccount: (accountId: string, reason: string, requestKey?: string) => request<void>(`/api/v1/admin/blocked-source-accounts/${encodeURIComponent(accountId)}`, { method: "PUT", body: JSON.stringify({ reason }) }, true, requestKey),
      unblockAccount: (accountId: string, requestKey?: string) => request<void>(`/api/v1/admin/blocked-source-accounts/${encodeURIComponent(accountId)}`, { method: "DELETE" }, true, requestKey),
      setXAccountMonitoring: (id: Identifier, enabled: boolean, requestKey?: string) => request<void>(`/api/v1/admin/x-accounts/${encodeURIComponent(id)}/monitoring`, { method: "PUT", body: JSON.stringify({ enabled }) }, true, requestKey),
      simulateXPost: (id: Identifier, input: { postId: string; canonicalUrl: string; permittedText: string; publishedAt: string }, requestKey?: string) => request<{ id: Identifier }>(`/api/v1/admin/x-accounts/${encodeURIComponent(id)}/simulate-post`, { method: "POST", body: JSON.stringify(input) }, true, requestKey),
      operationsSummary: () => request<OperationalSummary>("/api/v1/admin/operations/summary", { cache: "no-store" })
    }
  };
}

let fakeUser: UserProfile | undefined;
let fakeComments: Comment[] = [];
const fakeAccounts: XAccount[] = [{
  id: fakeId(80), accountId: "demo-library", handle: "citylibrary", displayName: "City library", topics: ["Cities"],
  relevanceThreshold: 0.5, monitoringEnabled: true, createdAt: "2026-09-01T12:00:00Z", removedAt: null,
  lastSuccessfulSyncAt: "2026-09-01T12:00:00Z", lastSyncAttemptAt: "2026-09-01T12:00:00Z", rateLimitResetAt: null,
  rateLimitLimit: 100, rateLimitRemaining: 99, lastPostId: "library-hours", lastError: null, consecutiveErrors: 0, syncHealth: "healthy", version: 1
}];
const fakeSources: SourcePost[] = ["library-hours", "library-programme"].map((postId, index) => ({
  id: fakeId(81 + index), postId, accountId: "demo-library", handle: "citylibrary", status: "active",
  reconciliationState: "not_required", publishedAt: "2026-09-01T12:00:00Z", ingestedAt: "2026-09-01T12:01:00Z",
  monitoredAccountId: fakeId(80), canonicalUrl: `https://x.com/citylibrary/status/${postId}`,
  permittedText: index ? "The library programme includes an evening reading room." : "The city library is extending its evening opening hours.",
  complianceAction: null, complianceReason: null, complianceError: null, excludedAt: null, contentDeletedAt: null,
  associations: [], relationships: [], version: 1
}));
const fakeCandidates: StoryCandidate[] = [{
  id: fakeId(83), topic: "Cities", status: "drafted", createdAt: "2026-09-01T12:01:00Z",
  sourceIds: fakeSources.map((source) => source.id), articleId: fakeId(84)
}];
const fakeImages: ImageGeneration[] = [{
  id: fakeId(85), articleId: fakeId(84), prompt: "An abstract library illustration, not a documentary photograph.",
  altText: "Illustration of a library reading room", objectKey: "demo-library.png", provider: "fake", model: "deterministic",
  safetyStatus: "passed", createdAt: "2026-09-01T12:02:00Z", approvedAt: null
}];
const fakeBlocked: { accountId: string; reason: string; createdAt: string }[] = [];
fakeArticles.push({
  ...fakeArticles[1], id: fakeId(84), slug: "library-evening-review", headline: "Library evening hours — review draft",
  state: "DRAFTING", storyCandidateId: fakeId(83), publishedAt: null, imageApprovalRequired: true,
  pendingImageGenerationId: fakeId(85), warnings: ["Verify opening hours with the original source."], confidence: 0.86,
  sources: fakeSources.map((source) => ({ sourcePostId: source.id, account: source.handle, postId: source.postId, url: source.canonicalUrl, publishedAt: source.publishedAt }))
});
const fakeRevisions = new Map<string, RevisionView[]>();
function fakeArticleContent(input: Record<string, unknown>): { content: ArticleContent; body: string } {
  const content = input.content ?? contentFromBody(typeof input.body === "string" ? input.body : "");
  const error = contentError(content);
  if (error) throw new ApiError(400, { title: "Invalid content", status: 400, detail: error });
  const normalized: ArticleContent = { version: 1, blocks: (content as ArticleContent).blocks.map((block) =>
    block.type === "unordered_list" || block.type === "ordered_list" ? { ...block, items: block.items.map((item) => item.trim()) } : { ...block, text: block.text.trim() }) };
  return { content: normalized, body: contentPlainText(normalized) };
}
function recordFakeRevision(article: ApiArticle, reason: string) {
  const revisions = fakeRevisions.get(article.id) || [];
  const snapshot: RevisionSnapshot = {
    slug: article.slug, headline: article.headline, summary: article.summary, body: article.body, content: article.content,
    editorialContext: article.editorialContext, seoTitle: article.seoTitle, seoDescription: article.seoDescription,
    topic: article.topic, tags: article.tags, sources: article.sources, heroObjectKey: article.heroObjectKey || null,
    imageAltText: article.imageAltText || null, generatedImage: article.generatedImage,
    approvedImageGenerationId: article.approvedImageGenerationId, pendingImageGenerationId: article.pendingImageGenerationId,
    imageApprovalRequired: article.imageApprovalRequired, commentsEnabled: article.commentsEnabled,
    state: article.state, humanReviewRequired: !article.approvedAt, confidence: article.confidence, warnings: article.warnings,
    correctionNote: article.correctionNote, approvedBy: article.approvedBy, approvedAt: article.approvedAt,
    publishedAt: article.publishedAt, updatedAt: article.updatedAt
  };
  revisions.unshift({
    id: crypto.randomUUID(), revisionNumber: revisions.length + 1, reason, actorId: fakeUser?.id || null,
    createdAt: new Date().toISOString(), snapshot: structuredClone(snapshot), aiGenerationResult: null, legacy: false
  });
  fakeRevisions.set(article.id, revisions);
}
fakeArticles.forEach((article) => recordFakeRevision(article, "INITIAL"));
const fakeMutationResults = new Map<string, { fingerprint: string; result: Promise<unknown> }>();
const fakeSchedules: PublicationSchedule[] = [];

async function fakeLogin(email: string) {
  const normalized = email.trim().toLowerCase();
  const name = normalized.split("@")[0];
  const role = name === "admin" ? "ADMINISTRATOR" : name === "editor" ? "EDITOR" : name === "moderator" ? "MODERATOR" : "READER";
  const hash = [...normalized].reduce((value, character) => (Math.imul(value, 31) + character.charCodeAt(0)) >>> 0, 0);
  fakeUser = { id: fakeId(hash), email: normalized, displayName: role === "ADMINISTRATOR" ? "Administrator" : role[0] + role.slice(1).toLowerCase(), roles: [role] };
  if (typeof window !== "undefined") window.sessionStorage.setItem("nsangusa.fake-user", JSON.stringify(fakeUser));
}
function canonicalPayload(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonicalPayload);
  if (value && typeof value === "object") {
    const record = value as Record<string, unknown>;
    return Object.fromEntries(Object.keys(record).sort().map((key) => [key, canonicalPayload(record[key])]));
  }
  return value;
}
async function fakeRequest<T>(path: string, init: WebRequestInit): Promise<T> {
  const key = new Headers(init.headers).get("Idempotency-Key");
  if (!key) return structuredClone(await routeFakeRequest<T>(path, init));
  const identity = `${fakeUser?.id || "demo"}:${key}`;
  const payload = typeof init.body === "string" ? canonicalPayload(JSON.parse(init.body)) : null;
  const fingerprint = JSON.stringify({ method: init.method, path, payload });
  const previous = fakeMutationResults.get(identity);
  if (previous && previous.fingerprint !== fingerprint) throw new ApiError(409, { title: "Conflict", status: 409, detail: "This request key was already used for a different action." });
  if (previous) return structuredClone(await previous.result) as T;
  const result = routeFakeRequest<T>(path, init).then((value) => structuredClone(value));
  fakeMutationResults.set(identity, { fingerprint, result });
  try { return structuredClone(await result); }
  catch (error) { fakeMutationResults.delete(identity); throw error; }
}

async function routeFakeRequest<T>(path: string, init: WebRequestInit): Promise<T> {
  const method = init.method || "GET";
  const body = typeof init.body === "string" ? JSON.parse(init.body) as Record<string, unknown> : {};
  if (path === "/api/v1/auth/csrf") return { headerName: "X-XSRF-TOKEN", token: "fake-csrf" } as T;
  if (path === "/api/v1/auth/me") {
    if (!fakeUser && typeof window !== "undefined") {
      try {
        const stored = window.sessionStorage.getItem("nsangusa.fake-user");
        if (stored) fakeUser = JSON.parse(stored) as UserProfile;
      } catch { window.sessionStorage.removeItem("nsangusa.fake-user"); }
    }
    if (!fakeUser) throw new ApiError(401, { title: "Unauthorized", status: 401, detail: "Sign in to continue." });
    if (method === "PATCH") {
      if (typeof body.displayName !== "string" || !body.displayName.trim() || body.displayName.trim().length > 100) {
        throw new ApiError(400, { title: "Invalid profile", status: 400, detail: "Display name is invalid." });
      }
      fakeUser = { ...fakeUser, displayName: body.displayName.trim() };
      if (typeof window !== "undefined") window.sessionStorage.setItem("nsangusa.fake-user", JSON.stringify(fakeUser));
    }
    return fakeUser as T;
  }
  if (path === "/api/v1/auth/logout") {
    fakeUser = undefined;
    if (typeof window !== "undefined") window.sessionStorage.removeItem("nsangusa.fake-user");
    return undefined as T;
  }
  if (path === "/api/v1/auth/register") return { status: "verification_required" } as T;
  if (path === "/api/v1/auth/password-reset/request" || path === "/api/v1/auth/verify-email" || path === "/api/v1/auth/password-reset/confirm") return undefined as T;
  if (path.startsWith("/api/v1/articles?")) return fakeArticles.filter((article) => article.state === "PUBLISHED") as T;
  if (path.startsWith("/api/v1/search?") || path.startsWith("/api/v1/topics/") || path.startsWith("/api/v1/tags/")) {
    const url = new URL(path, "http://local");
    const searchTerm = path.startsWith("/api/v1/search?") ? (url.searchParams.get("q") || "").toLowerCase() : "";
    const segment = decodeURIComponent(url.pathname.split("/").at(-1) || "").toLowerCase();
    const items = fakeArticles.filter((article) => {
      if (article.state !== "PUBLISHED") return false;
      if (path.startsWith("/api/v1/topics/")) return article.topic.toLowerCase() === segment;
      if (path.startsWith("/api/v1/tags/")) return article.tags.some((tag) => tag.toLowerCase() === segment);
      return `${article.headline} ${article.summary} ${article.tags.join(" ")}`.toLowerCase().includes(searchTerm);
    }).map((article) => ({
      articleId: article.id, slug: article.slug, headline: article.headline, summary: article.summary,
      topic: article.topic, tags: article.tags, publishedAt: article.publishedAt, rank: 1
    }));
    return { items, page: 0, size: 20, total: items.length } as T;
  }
  const publicArticle = path.match(/^\/api\/v1\/articles\/([^/]+)$/);
  if (publicArticle && method === "GET") {
    const article = fakeArticles.find((item) => item.slug === decodeURIComponent(publicArticle[1]) && item.state === "PUBLISHED");
    if (!article) throw new ApiError(404, { title: "Not found", status: 404, detail: "Article not found." });
    return article as T;
  }
  const comments = path.match(/^\/api\/v1\/articles\/([^/]+)\/comments$/);
  if (comments) {
    if (method === "GET") return fakeComments.filter((comment) => comment.state === "approved") as T;
    const comment = { id: fakeId(100 + fakeComments.length), authorId: fakeUser?.id || fakeId(0), body: String(body.body), parentId: body.parentId as string | null, state: "pending" };
    fakeComments.push(comment);
    return { id: comment.id } as T;
  }
  if (path === "/api/v1/newsletter/subscriptions") return { subscriptionId: fakeId(97), status: "verification_required" } as T;
  if (path.startsWith("/api/v1/newsletter/confirm") || path.startsWith("/api/v1/newsletter/unsubscribe")) return undefined as T;
  if (path.startsWith("/api/v1/admin/articles?")) {
    const query = new URL(path, "http://local").searchParams;
    const page = Number(query.get("page") || 0), size = Number(query.get("size") || 20);
    const items = fakeArticles.filter((article) => !query.get("state") || article.state === query.get("state"));
    return { items: items.slice(page * size, (page + 1) * size), page, size, total: items.length } as T;
  }
  if (path === "/api/v1/admin/publication-policy") return {
    policy: "HUMAN_REVIEW_ALWAYS", confidenceThreshold: 0.9, topicRules: {}, approvedSourceAccounts: ["citylibrary"],
    humanPublicationAllowed: true,
    explanation: "Articles require human approval before publication. Automatic publication is disabled."
  } as T;
  if (path.startsWith("/api/v1/admin/publication-schedules?")) {
    const query = new URL(path, "http://local").searchParams;
    const page = Number(query.get("page") || 0), size = Number(query.get("size") || 20);
    const items = fakeSchedules.filter((schedule) => (!query.get("status") || schedule.status === query.get("status")) && (!query.get("articleId") || schedule.articleId === query.get("articleId")));
    return { items: items.slice(page * size, (page + 1) * size), page, size, total: items.length } as T;
  }
  const scheduleRoute = path.match(/^\/api\/v1\/admin\/publication-schedules\/([^/]+)(\/cancel)?$/);
  if (scheduleRoute) {
    const schedule = fakeSchedules.find((item) => item.id === scheduleRoute[1]);
    if (!schedule) throw new ApiError(404, { title: "Not found", status: 404, detail: "Publication schedule not found." });
    if (schedule.version !== body.expectedVersion || !["scheduled", "failed"].includes(schedule.status)) throw new ApiError(409, { title: "Conflict", status: 409, detail: "Refresh the queue. Only current scheduled or failed publications can change." });
    const article = fakeArticles.find((item) => item.id === schedule.articleId)!;
    if (scheduleRoute[2] && method === "POST") {
      schedule.status = "cancelled"; schedule.completedAt = new Date().toISOString();
      if (article.state === "SCHEDULED") {
        article.state = "APPROVED"; article.version += 1; article.updatedAt = new Date().toISOString();
        recordFakeRevision(article, "SCHEDULE_CANCELLED");
      }
    } else if (method === "PATCH") {
      if (article.state !== "SCHEDULED" || article.version !== schedule.articleVersion) throw new ApiError(409, { title: "Conflict", status: 409, detail: "Cancel this schedule and review the changed article." });
      if (!(new Date(String(body.publishAt)).getTime() > Date.now())) throw new ApiError(400, { title: "Invalid schedule", status: 400, detail: "Choose a future publication date and time." });
      schedule.publishAt = String(body.publishAt); schedule.status = "scheduled"; schedule.completedAt = null;
    }
    schedule.updatedBy = fakeUser?.id || fakeId(99); schedule.updatedAt = new Date().toISOString(); schedule.version += 1;
    return schedule as T;
  }
  if (path.startsWith("/api/v1/admin/story-candidates?")) {
    const query = new URL(path, "http://local").searchParams;
    const page = Number(query.get("page") || 0), size = Number(query.get("size") || 20);
    return { items: fakeCandidates.slice(page * size, (page + 1) * size), page, size, total: fakeCandidates.length } as T;
  }
  const regenerate = path.match(/^\/api\/v1\/admin\/story-candidates\/([^/]+)\/regenerate$/);
  if (regenerate) {
    const original = fakeCandidates.find((candidate) => candidate.id === regenerate[1]);
    if (!original) throw new ApiError(404, { title: "Not found", status: 404, detail: "Candidate not found." });
    const id = fakeId(500 + fakeCandidates.length);
    fakeCandidates.unshift({ ...original, id, sourceIds: [...original.sourceIds], articleId: null, status: "pending", createdAt: new Date().toISOString() });
    return { id } as T;
  }
  if (path.startsWith("/api/v1/admin/ai-requests?")) {
    const storyCandidateId = new URL(path, "http://local").searchParams.get("storyCandidateId");
    return [{
      id: fakeId(86), storyCandidateId, operation: "analysis", provider: "fake", model: "deterministic",
      promptVersion: "analysis-v1", status: "completed", errorCode: null, createdAt: "2026-09-01T12:01:00Z",
      completedAt: "2026-09-01T12:01:01Z", inputTokens: 100, outputTokens: 80,
      result: { analysis: "The sources describe an evening library programme.", confidence: 0.86,
        warnings: ["Verify opening hours with the original source."],
        claims: [{ text: "The library will open later.", classification: "source_supported", supportingSourceIds: [fakeId(81)] }] }
    }] as T;
  }
  if (path.startsWith("/api/v1/admin/source-posts?")) {
    const query = new URL(path, "http://local").searchParams;
    return fakeSources.filter((source) => (!query.get("status") || source.status === query.get("status")) && (!query.get("accountId") || source.accountId === query.get("accountId"))) as T;
  }
  const sourceRoute = path.match(/^\/api\/v1\/admin\/source-posts\/([^/]+)(\/exclude)?$/);
  if (sourceRoute) {
    const source = fakeSources.find((item) => item.id === sourceRoute[1]);
    if (!source) throw new ApiError(404, { title: "Not found", status: 404, detail: "Source not found." });
    if (sourceRoute[2]) { source.status = "excluded"; source.excludedAt = new Date().toISOString(); return undefined as T; }
    return source as T;
  }
  const images = path.match(/^\/api\/v1\/admin\/articles\/([^/]+)\/images(\/regenerate|\/fallback)?$/);
  if (images) {
    if (!images[2]) return fakeImages.filter((image) => image.articleId === images[1]) as T;
    const article = fakeArticles.find((item) => item.id === images[1]);
    if (!article) throw new ApiError(404, { title: "Not found", status: 404, detail: "Article not found." });
    if (!["DRAFTING", "AWAITING_REVIEW", "APPROVED"].includes(article.state)) throw new ApiError(409, { title: "Conflict", status: 409, detail: "This article cannot request a replacement image." });
    const fallback = images[2] === "/fallback";
    if (fallback && body.expectedVersion !== article.version) throw new ApiError(409, { title: "Conflict", status: 409, detail: "Article version does not match." });
    if (fallback && (!String(body.reason || "").trim() || !String(body.altText || "").trim())) throw new ApiError(400, { title: "Invalid fallback", status: 400, detail: "Alternative text and an editorial reason are required." });
    const generation: ImageGeneration = { id: fakeId(600 + fakeImages.length), articleId: images[1], prompt: fallback ? `Neutral editorial fallback. Reason: ${String(body.reason)}` : String(body.prompt), altText: String(body.altText), objectKey: "demo-image.png", provider: fallback ? "nsangusa-editorial" : "fake", model: fallback ? "neutral-illustration-v1" : "deterministic", safetyStatus: "review_required", createdAt: new Date().toISOString(), approvedAt: null };
    fakeImages.unshift(generation);
    article.pendingImageGenerationId = generation.id; article.imageApprovalRequired = !article.approvedImageGenerationId;
    recordFakeRevision(article, "IMAGE_REQUESTED");
    return { eventId: generation.id } as T;
  }
  const imageApproval = path.match(/^\/api\/v1\/admin\/image-generations\/([^/]+)\/approve$/);
  if (imageApproval) {
    const image = fakeImages.find((item) => item.id === imageApproval[1]);
    const article = fakeArticles.find((item) => item.id === image?.articleId);
    if (image && article) {
      image.approvedAt = new Date().toISOString();
      Object.assign(article, { approvedImageGenerationId: image.id, pendingImageGenerationId: null, imageApprovalRequired: false, heroObjectKey: image.objectKey, imageAltText: image.altText, generatedImage: !(image.provider === "nsangusa-editorial" && image.model === "neutral-illustration-v1"), version: article.version + 1 });
      if (article.state === "DRAFTING") article.state = "AWAITING_REVIEW";
      recordFakeRevision(article, "IMAGE_APPROVED");
    }
    return undefined as T;
  }
  if (path === "/api/v1/admin/articles" && method === "POST") {
    const id = fakeId(fakeArticles.length + 10);
    fakeArticles.push({ id, slug: String(body.slugSuggestion), headline: String(body.headline), summary: String(body.summary), ...fakeArticleContent(body), editorialContext: body.editorialContext as string | null, topic: String(body.topic), tags: body.tags as string[], state: "AWAITING_REVIEW", generatedImage: false, commentsEnabled: Boolean(body.commentsEnabled), publishedAt: null, updatedAt: new Date().toISOString(), version: 1, sources: body.sources as ArticleSource[], warnings: [], confidence: 1, storyCandidateId: null, seoTitle: String(body.seoTitle), seoDescription: String(body.seoDescription), approvedImageGenerationId: null, pendingImageGenerationId: null, imageApprovalRequired: false, correctionNote: null, approvedBy: null, approvedAt: null });
    recordFakeRevision(fakeArticles.at(-1)!, "CREATED");
    return { id } as T;
  }
  const revisionRoute = path.match(/^\/api\/v1\/admin\/articles\/([^/]+)\/revisions(\/compare)?\?/);
  if (revisionRoute && method === "GET") {
    const items = fakeRevisions.get(revisionRoute[1]);
    if (!items) throw new ApiError(404, { title: "Not found", status: 404, detail: "Article not found." });
    const query = new URL(path, "http://local").searchParams;
    if (revisionRoute[2]) {
      const from = items.find((revision) => revision.revisionNumber === Number(query.get("from")));
      const to = items.find((revision) => revision.revisionNumber === Number(query.get("to")));
      if (!from || !to) throw new ApiError(404, { title: "Not found", status: 404, detail: "Revision not found." });
      const changedFields = Object.keys(from.snapshot || {}).filter((field) => JSON.stringify(from.snapshot?.[field as keyof RevisionSnapshot]) !== JSON.stringify(to.snapshot?.[field as keyof RevisionSnapshot]));
      return { from, to, changedFields } as T;
    }
    const page = Number(query.get("page") || 0), size = Number(query.get("size") || 20);
    return { items: items.slice(page * size, (page + 1) * size), page, size, total: items.length } as T;
  }
  const correction = path.match(/^\/api\/v1\/admin\/articles\/([^/]+)\/corrections$/);
  if (correction && method === "POST") {
    const article = fakeArticles.find((item) => item.id === correction[1]);
    if (!article) throw new ApiError(404, { title: "Not found", status: 404, detail: "Article not found." });
    if (article.version !== body.expectedVersion || !["PUBLISHED", "UNPUBLISHED"].includes(article.state)) throw new ApiError(409, { title: "Conflict", status: 409, detail: "Refresh the article and check its publication state." });
    if (typeof body.note !== "string" || !body.note.trim()) throw new ApiError(400, { title: "Invalid correction", status: 400, detail: "A public correction note is required." });
    Object.assign(article, { state: "AWAITING_REVIEW", correctionNote: body.note.trim(), approvedBy: null, approvedAt: null, updatedAt: new Date().toISOString(), version: article.version + 1 });
    recordFakeRevision(article, "CORRECTION_STARTED");
    return undefined as T;
  }
  const adminArticle = path.match(/^\/api\/v1\/admin\/articles\/([^/?]+)(?:\?[^/]*)?$/);
  if (adminArticle && method === "GET") {
    const article = fakeArticles.find((item) => item.id === adminArticle[1]);
    if (!article) throw new ApiError(404, { title: "Not found", status: 404, detail: "Article not found." });
    return article as T;
  }
  if (adminArticle && method === "PUT") {
    const article = fakeArticles.find((item) => item.id === adminArticle[1]);
    if (!article) throw new ApiError(404, { title: "Not found", status: 404, detail: "Article not found." });
    const expectedVersion = Number(new URL(path, "http://local").searchParams.get("expectedVersion"));
    if (expectedVersion !== article.version) throw new ApiError(409, { title: "Conflict", status: 409, detail: "Article version does not match. Refresh and retry." });
    if (!["DRAFTING", "AWAITING_REVIEW", "APPROVED", "REJECTED"].includes(article.state)) throw new ApiError(409, { title: "Conflict", status: 409, detail: "Cancel a schedule or start a correction before editing." });
    Object.assign(article, {
      headline: String(body.headline), summary: String(body.summary), ...fakeArticleContent(body),
      seoTitle: String(body.seoTitle), seoDescription: String(body.seoDescription),
      editorialContext: body.editorialContext as string | null, topic: String(body.topic), tags: body.tags as string[],
      commentsEnabled: Boolean(body.commentsEnabled), sources: body.sources as ArticleSource[], state: "AWAITING_REVIEW",
      updatedAt: new Date().toISOString(), version: article.version + 1, approvedBy: null, approvedAt: null
    });
    recordFakeRevision(article, "EDITED");
    return undefined as T;
  }
  const transition = path.match(/^\/api\/v1\/admin\/articles\/([^/]+)\/(approve|publish|schedule|reject|unpublish|restore|archive)(?:\?[^/]*)?$/);
  if (transition) {
    const article = fakeArticles.find((item) => item.id === transition[1]);
    if (!article) throw new ApiError(404, { title: "Not found", status: 404, detail: "Article not found." });
    const expectedVersion = new URL(path, "http://local").searchParams.get("expectedVersion");
    if (expectedVersion !== null && Number(expectedVersion) !== article.version) throw new ApiError(409, { title: "Conflict", status: 409, detail: "Article version does not match. Refresh and review the current revision before retrying." });
    const allowed: Record<string, ArticleState[]> = { approve: ["AWAITING_REVIEW"], publish: ["APPROVED", "SCHEDULED"], schedule: ["APPROVED"], reject: ["AWAITING_REVIEW"], unpublish: ["PUBLISHED"], restore: ["UNPUBLISHED"], archive: ["UNPUBLISHED", "REJECTED"] };
    if (!allowed[transition[2]].includes(article.state) || (["approve", "publish", "schedule", "restore"].includes(transition[2]) && article.imageApprovalRequired)) {
      throw new ApiError(409, { title: "Conflict", status: 409, detail: "The article state or image approval prevents this action." });
    }
    if (transition[2] === "schedule" && !(new Date(String(body.publishAt)).getTime() > Date.now())) {
      throw new ApiError(400, { title: "Invalid schedule", status: 400, detail: "Choose a future publication date and time." });
    }
    const state: Record<string, ArticleState> = { approve: "APPROVED", publish: "PUBLISHED", schedule: "SCHEDULED", reject: "REJECTED", unpublish: "UNPUBLISHED", restore: "PUBLISHED", archive: "ARCHIVED" };
    article.state = state[transition[2]]; article.version += 1; article.updatedAt = new Date().toISOString();
    if (article.state === "APPROVED") { article.approvedBy = fakeUser?.id || fakeId(99); article.approvedAt = article.updatedAt; }
    if (article.state === "PUBLISHED") article.publishedAt ||= article.updatedAt;
    recordFakeRevision(article, transition[2].toUpperCase());
    if (transition[2] === "schedule") {
      const scheduleId = crypto.randomUUID();
      fakeSchedules.unshift({
        id: scheduleId, articleId: article.id, publishAt: String(body.publishAt), status: "scheduled", version: 0,
        articleVersion: article.version, scheduledBy: fakeUser?.id || fakeId(99), updatedBy: fakeUser?.id || fakeId(99),
        createdAt: article.updatedAt, updatedAt: article.updatedAt, completedAt: null, lastAttemptAt: null, attemptCount: 0, lastError: null
      });
      return { scheduleId } as T;
    }
    if (transition[2] === "publish") {
      for (const schedule of fakeSchedules.filter((value) => value.articleId === article.id && ["scheduled", "failed"].includes(value.status))) {
        schedule.status = "published"; schedule.completedAt = article.updatedAt; schedule.updatedAt = article.updatedAt; schedule.version += 1;
      }
    }
    return undefined as T;
  }
  if (path.includes("/moderate")) { const comment = fakeComments.find((item) => path.includes(item.id)); if (comment) comment.state = String(body.decision); return undefined as T; }
  if (path === "/api/v1/admin/x-accounts") {
    if (method === "GET") return fakeAccounts.filter((account) => !account.removedAt) as T;
    const id = fakeId(700 + fakeAccounts.length);
    fakeAccounts.push({ ...fakeAccounts[0], id, accountId: String(body.accountId), handle: String(body.handle), displayName: String(body.displayName), topics: body.topics as string[], relevanceThreshold: Number(body.relevanceThreshold), lastSuccessfulSyncAt: null, syncHealth: "never_synced" });
    return { id } as T;
  }
  if (path === "/api/v1/admin/x-accounts/capabilities") return { simulationEnabled: true } as T;
  if (path.startsWith("/api/v1/admin/x-accounts/resolve?")) {
    const handle = (new URL(path, "http://local").searchParams.get("handle") || "").replace(/^@/, "");
    if (!/^[A-Za-z0-9_]{1,15}$/.test(handle)) throw new ApiError(400, { title: "Invalid handle", status: 400, detail: "Enter a valid X handle." });
    const accountId = String([...handle.toLowerCase()].reduce((hash, character) => ((hash * 31) + character.charCodeAt(0)) >>> 0, 1));
    return { accountId, handle, displayName: handle, simulated: true } as T;
  }
  if (path === "/api/v1/admin/blocked-source-accounts") return fakeBlocked as T;
  const blocked = path.match(/^\/api\/v1\/admin\/blocked-source-accounts\/([^/]+)$/);
  if (blocked) {
    const index = fakeBlocked.findIndex((item) => item.accountId === blocked[1]);
    if (index !== -1) fakeBlocked.splice(index, 1);
    if (method === "PUT") fakeBlocked.push({ accountId: blocked[1], reason: String(body.reason), createdAt: new Date().toISOString() });
    return undefined as T;
  }
  const accountRoute = path.match(/^\/api\/v1\/admin\/x-accounts\/([^/]+)(?:\/(monitoring|simulate-post))?$/);
  if (accountRoute) {
    const account = fakeAccounts.find((item) => item.id === accountRoute[1]);
    if (!account) throw new ApiError(404, { title: "Not found", status: 404, detail: "Account not found." });
    if (accountRoute[2] === "simulate-post") {
      const postId = String(body.postId);
      if (!/^\d{1,30}$/.test(postId)) throw new ApiError(400, { title: "Bad Request", status: 400, detail: "X postId must be the official numeric post ID" });
      const canonicalUrl = `https://x.com/${account.handle.replace(/^@/, "")}/status/${postId}`;
      const suppliedUrl = String(body.canonicalUrl).trim().split(/[?#]/, 1)[0];
      if (suppliedUrl.toLowerCase() !== canonicalUrl.toLowerCase()) throw new ApiError(400, { title: "Bad Request", status: 400, detail: "Source URL must be the official canonical X post URL" });
      const publishedAt = new Date(String(body.publishedAt));
      if (!Number.isFinite(publishedAt.getTime()) || publishedAt.getTime() > Date.now() + 300_000) throw new ApiError(400, { title: "Bad Request", status: 400, detail: "A valid X publication timestamp is required" });
      const id = fakeId(800 + fakeSources.length);
      fakeSources.push({ ...fakeSources[0], id, monitoredAccountId: account.id, accountId: account.accountId, handle: account.handle, postId, canonicalUrl, permittedText: String(body.permittedText), publishedAt: publishedAt.toISOString(), status: "active" });
      return { id } as T;
    }
    if (accountRoute[2] === "monitoring") account.monitoringEnabled = Boolean(body.enabled);
    else if (method === "DELETE") account.removedAt = new Date().toISOString();
    else Object.assign(account, body);
    return undefined as T;
  }
  if (path === "/api/v1/admin/operations/summary") return { status: "available", checkedAt: new Date().toISOString(), details: "Demo operational summary." } as T;
  throw new ApiError(404, { title: "Not found", status: 404, detail: `No fake route for ${path}.` });
}

export const api = createApiClient();
