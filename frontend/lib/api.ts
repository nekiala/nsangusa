export type Identifier = string;
export type ArticleState = "DISCOVERED" | "ANALYZING" | "DRAFTING" | "AWAITING_REVIEW" | "APPROVED" | "SCHEDULED" | "PUBLISHED" | "UNPUBLISHED" | "REJECTED" | "ARCHIVED";
export type ArticleSource = { sourcePostId: Identifier; account: string; postId: string; url: string; publishedAt: string };
export type ApiArticle = {
  id: Identifier; slug: string; headline: string; summary: string; body: string; editorialContext: string | null;
  topic: string; tags: string[]; state: ArticleState; heroObjectKey?: string | null; imageAltText?: string | null;
  generatedImage: boolean; commentsEnabled: boolean; publishedAt: string | null; updatedAt: string; version: number;
  sources: ArticleSource[]; warnings: string[]; confidence: number;
};
export type ArticleCommand = {
  headline: string; summary: string; body: string; editorialContext?: string | null; seoTitle: string;
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
];

function configuredMode(): "api" | "fake" {
  return process.env.NEXT_PUBLIC_API_MODE === "fake" ? "fake" : "api";
}

function apiBaseUrl() {
  if (typeof window === "undefined") return process.env.NSANGUSA_API_URL || "http://localhost:8080";
  return process.env.NEXT_PUBLIC_API_URL ?? "";
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

  async function request<T>(path: string, init: WebRequestInit = {}, unsafe = false): Promise<T> {
    if (mode === "fake") return fakeRequest<T>(path, init);
    if (unsafe) {
      csrf ||= await bootstrapCsrf();
    }
    const headers = new Headers(init.headers);
    if (unsafe && csrf) headers.set(csrf.headerName, csrf.token);
    if (init.body && !headers.has("Content-Type") && !(init.body instanceof URLSearchParams)) headers.set("Content-Type", "application/json");
    const noStore = unsafe || init.cache === "no-store";
    const response = await fetcher(`${baseUrl}${path}`, {
      ...init, headers, credentials: "include", cache: noStore ? "no-store" : "default",
      ...(noStore ? { next: { revalidate: 0 } } : { next: { revalidate: 30 } })
    } as WebRequestInit);
    if (!response.ok) {
      let value: unknown;
      try { value = await response.json(); } catch { /* A gateway may return an empty error response. */ }
      throw new ApiError(response.status, problemFrom(response, value));
    }
    if (response.status === 204 || response.status === 205 || !response.headers.get("content-type")?.includes("application/json")) return undefined as T;
    return response.json() as Promise<T>;
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
    if (mode === "fake") return fakeLogin(email);
    csrf ||= await bootstrapCsrf();
    const headers = new Headers();
    headers.set(csrf.headerName, csrf.token);
    const response = await fetcher(`${baseUrl}/api/v1/auth/login`, {
      method: "POST", body: new URLSearchParams({ username: email, password }), redirect: "manual",
      headers, credentials: "include", cache: "no-store", next: { revalidate: 0 }
    } as WebRequestInit);
    if (response.ok || response.status === 302 || response.status === 303 || response.type === "opaqueredirect") return;
    let value: unknown;
    try { value = await response.json(); } catch { /* handled below */ }
    throw new ApiError(response.status, problemFrom(response, value));
  }

  return {
    bootstrapCsrf,
    auth: {
      register: (input: { email: string; password: string; displayName: string }) => request<{ id: Identifier; status: "verification_required" }>("/api/v1/auth/register", { method: "POST", body: JSON.stringify(input) }, true),
      login,
      me: () => request<UserProfile>("/api/v1/auth/me", { cache: "no-store" }),
      logout: () => request<void>("/api/v1/auth/logout", { method: "POST", redirect: "manual" }, true),
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
      subscribe: (email: string, frequency: "immediate" | "daily" | "weekly" | "all" = "weekly") => request<{ subscriptionId: Identifier; status: string }>("/api/v1/newsletter/subscriptions", { method: "POST", body: JSON.stringify({ email, consentSource: "website", frequency }) }, true),
      confirm: (id: Identifier, token: string) => request<void>(`/api/v1/newsletter/confirm?id=${encodeURIComponent(id)}&token=${encodeURIComponent(token)}`),
      unsubscribe: (id: Identifier, token: string) => request<void>(`/api/v1/newsletter/unsubscribe?id=${encodeURIComponent(id)}&token=${encodeURIComponent(token)}`, { method: "POST" }, true)
    },
    admin: {
      article: (id: Identifier) => request<ApiArticle>(`/api/v1/admin/articles/${encodeURIComponent(id)}`, { cache: "no-store" }),
      createArticle: (input: ArticleCommand) => request<{ id: Identifier }>("/api/v1/admin/articles", { method: "POST", body: JSON.stringify(input) }, true),
      editArticle: (id: Identifier, expectedVersion: number, input: ArticleCommand) => request<void>(`/api/v1/admin/articles/${encodeURIComponent(id)}?expectedVersion=${encodeURIComponent(expectedVersion)}`, { method: "PUT", body: JSON.stringify(input) }, true),
      approveArticle: (id: Identifier) => request<void>(`/api/v1/admin/articles/${encodeURIComponent(id)}/approve`, { method: "POST" }, true),
      publishArticle: (id: Identifier) => request<void>(`/api/v1/admin/articles/${encodeURIComponent(id)}/publish`, { method: "POST" }, true),
      scheduleArticle: (id: Identifier, publishAt: string) => request<{ scheduleId: Identifier }>(`/api/v1/admin/articles/${encodeURIComponent(id)}/schedule`, { method: "POST", body: JSON.stringify({ publishAt }) }, true),
      rejectArticle: (id: Identifier) => request<void>(`/api/v1/admin/articles/${encodeURIComponent(id)}/reject`, { method: "POST" }, true),
      unpublishArticle: (id: Identifier) => request<void>(`/api/v1/admin/articles/${encodeURIComponent(id)}/unpublish`, { method: "POST" }, true),
      restoreArticle: (id: Identifier) => request<void>(`/api/v1/admin/articles/${encodeURIComponent(id)}/restore`, { method: "POST" }, true),
      archiveArticle: (id: Identifier) => request<void>(`/api/v1/admin/articles/${encodeURIComponent(id)}/archive`, { method: "POST" }, true),
      moderateComment: (id: Identifier, decision: "approved" | "rejected" | "spam" | "deleted") => request<void>(`/api/v1/admin/comments/${encodeURIComponent(id)}/moderate`, { method: "POST", body: JSON.stringify({ decision }) }, true),
      addXAccount: (input: { accountId: string; handle: string; displayName: string; topics: string[]; relevanceThreshold: number }) => request<{ id: Identifier }>("/api/v1/admin/x-accounts", { method: "POST", body: JSON.stringify(input) }, true),
      setXAccountMonitoring: (id: Identifier, enabled: boolean) => request<void>(`/api/v1/admin/x-accounts/${encodeURIComponent(id)}/monitoring`, { method: "PUT", body: JSON.stringify({ enabled }) }, true),
      simulateXPost: (id: Identifier, input: { postId: string; canonicalUrl: string; permittedText: string; publishedAt: string }) => request<{ id: Identifier }>(`/api/v1/admin/x-accounts/${encodeURIComponent(id)}/simulate-post`, { method: "POST", body: JSON.stringify(input) }, true),
      operationsSummary: () => request<OperationalSummary>("/api/v1/admin/operations/summary", { cache: "no-store" })
    }
  };
}

let fakeUser: UserProfile | undefined;
let fakeComments: Comment[] = [];
async function fakeLogin(email: string) {
  fakeUser = { id: fakeId(99), email, displayName: "Demo editor", roles: ["EDITOR", "ADMINISTRATOR"] };
  if (typeof window !== "undefined") window.sessionStorage.setItem("nsangusa.fake-user", JSON.stringify(fakeUser));
}
async function fakeRequest<T>(path: string, init: WebRequestInit): Promise<T> {
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
    return fakeUser as T;
  }
  if (path === "/api/v1/auth/logout") {
    fakeUser = undefined;
    if (typeof window !== "undefined") window.sessionStorage.removeItem("nsangusa.fake-user");
    return undefined as T;
  }
  if (path === "/api/v1/auth/register") return { id: fakeId(98), status: "verification_required" } as T;
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
  if (path === "/api/v1/admin/articles" && method === "POST") {
    const id = fakeId(fakeArticles.length + 10);
    fakeArticles.push({ id, slug: String(body.slugSuggestion), headline: String(body.headline), summary: String(body.summary), body: String(body.body), editorialContext: body.editorialContext as string | null, topic: String(body.topic), tags: body.tags as string[], state: "AWAITING_REVIEW", generatedImage: false, commentsEnabled: Boolean(body.commentsEnabled), publishedAt: null, updatedAt: new Date().toISOString(), version: 1, sources: body.sources as ArticleSource[], warnings: [], confidence: 1 });
    return { id } as T;
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
    Object.assign(article, {
      headline: String(body.headline), summary: String(body.summary), body: String(body.body),
      editorialContext: body.editorialContext as string | null, topic: String(body.topic), tags: body.tags as string[],
      commentsEnabled: Boolean(body.commentsEnabled), sources: body.sources as ArticleSource[], state: "AWAITING_REVIEW",
      updatedAt: new Date().toISOString(), version: article.version + 1
    });
    return undefined as T;
  }
  const transition = path.match(/^\/api\/v1\/admin\/articles\/([^/]+)\/(approve|publish|schedule|reject|unpublish|restore|archive)$/);
  if (transition) {
    const article = fakeArticles.find((item) => item.id === transition[1]);
    if (!article) throw new ApiError(404, { title: "Not found", status: 404, detail: "Article not found." });
    const state: Record<string, ArticleState> = { approve: "APPROVED", publish: "PUBLISHED", schedule: "SCHEDULED", reject: "REJECTED", unpublish: "UNPUBLISHED", restore: "PUBLISHED", archive: "ARCHIVED" };
    article.state = state[transition[2]]; article.version += 1;
    if (article.state === "PUBLISHED") article.publishedAt = new Date().toISOString();
    return transition[2] === "schedule" ? { scheduleId: fakeId(96) } as T : undefined as T;
  }
  if (path.includes("/moderate")) { const comment = fakeComments.find((item) => path.includes(item.id)); if (comment) comment.state = String(body.decision); return undefined as T; }
  if (path === "/api/v1/admin/x-accounts" && method === "POST") return { id: fakeId(95) } as T;
  if (path.includes("/monitoring")) return undefined as T;
  if (path.includes("/simulate-post")) return { id: fakeId(94) } as T;
  if (path === "/api/v1/admin/operations/summary") return { status: "available", checkedAt: new Date().toISOString(), details: "Demo operational summary." } as T;
  throw new ApiError(404, { title: "Not found", status: 404, detail: `No fake route for ${path}.` });
}

export const api = createApiClient();
