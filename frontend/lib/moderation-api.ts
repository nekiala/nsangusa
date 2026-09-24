import { api, ApiError, type Page, type UserProfile } from "@/lib/api";

export type CommunityComment = {
  id: string; authorId: string; authorName?: string; body: string; parentId: string | null; state: string;
  createdAt: string; editedAt: string | null; deletedByAuthor: boolean; deleted: boolean; version: number;
};
export type Discussion = {
  comments: CommunityComment[]; viewerId: string | null; authenticated: boolean; eligible: boolean;
  enabled: boolean; requireApproval: boolean; editingWindowMinutes: number; maximumReplyDepth: number;
  privilegeStatus: string; suspendedUntil: string | null; canComment: boolean;
};
export type ModerationItem = CommunityComment & {
  articleId: string; spamScore: number; spamReason: string | null; openReports: number;
  articleHeadline: string; articleSlug: string;
};
export type CommentReport = { id: string; commentId: string; reporterId: string; reason: string; details: string | null; status: string; createdAt: string };
export type ModerationHistory = { id: string; moderatorId: string; previousState: string; action: string; reason: string; createdAt: string };
export type Privilege = { userId: string; status: string; suspendedUntil: string | null; reason: string | null; moderatorId: string | null; updatedAt: string; version: number };
export type PrivilegeHistory = { id: string; action: string; suspendedUntil: string | null; reason: string; moderatorId: string; createdAt: string };
export type CommentSettings = { enabled: boolean; requireApproval: boolean; editingWindowMinutes: number; reviewSpamThreshold: number; rejectSpamThreshold: number; reportEscalationThreshold: number; updatedAt: string; version: number };
export type ArticleCommentSettings = { articleId: string; enabledOverride: boolean | null; requireApprovalOverride: boolean | null; updatedAt: string; version: number };
export type CommentArticle = { id: string; headline: string; slug: string; state: string; commentsEnabled: boolean };
export type CommentingUser = { id: string; displayName: string; staff: boolean; active: boolean };
export type Decision = "approved" | "rejected" | "spam" | "deleted";
type Transport = Pick<typeof api, "mode" | "request" | "auth">;
const id = (value: number) => `00000000-0000-4000-8000-${String(value).padStart(12, "0")}`;
const now = () => new Date().toISOString();
const error = (status: number, detail: string): never => { throw new ApiError(status, { status, title: status === 403 ? "Forbidden" : "Request failed", detail }); };
const staffRoles = ["MODERATOR", "ADMINISTRATOR"];
const readerRoles = ["READER", "EDITOR", ...staffRoles];
const paged = <T,>(items: T[], page: number, size = 20): Page<T> => ({ items: items.slice(page * size, (page + 1) * size), page, size, total: items.length });

export function createModerationApi(transport: Transport = api) {
  const fixtureArticle: CommentArticle = { id: id(1), headline: "The work of paying attention", slug: "the-work-of-paying-attention", state: "PUBLISHED", commentsEnabled: true };
  const fixtureUsers: CommentingUser[] = [{ id: id(98), displayName: "Demo reader", staff: false, active: true }, { id: id(99), displayName: "Demo editor", staff: true, active: true }];
  const records: ModerationItem[] = [{ id: id(701), authorId: id(98), authorName: "Demo reader", body: "A considered response awaiting review.", parentId: null, state: "pending", createdAt: now(), editedAt: null, deleted: false, deletedByAuthor: false, version: 0, articleId: fixtureArticle.id, articleHeadline: fixtureArticle.headline, articleSlug: fixtureArticle.slug, spamScore: 0, spamReason: null, openReports: 0 }];
  let settings: CommentSettings = { enabled: true, requireApproval: true, editingWindowMinutes: 15, reviewSpamThreshold: 0.55, rejectSpamThreshold: 0.9, reportEscalationThreshold: 3, updatedAt: now(), version: -1 };
  const articleSettings = new Map<string, ArticleCommentSettings>();
  const privileges = new Map<string, Privilege>();
  const histories = new Map<string, ModerationHistory[]>();
  const privilegeHistories = new Map<string, PrivilegeHistory[]>();
  const reports: CommentReport[] = [];
  const keys = new Map<string, string>();

  async function request<T>(path: string, method?: string, body?: unknown): Promise<T> {
    if (!method) return transport.request<T>(path, { cache: "no-store" });
    const fingerprint = JSON.stringify([path, method, body]);
    if (!keys.has(fingerprint) && keys.size >= 100) throw new Error("Too many unconfirmed comment changes. Reconcile their outcomes before submitting more.");
    const key = keys.get(fingerprint) || crypto.randomUUID();
    keys.set(fingerprint, key);
    try {
      const result = await transport.request<T>(path, { method, body: body === undefined ? undefined : JSON.stringify(body), headers: { "Idempotency-Key": key } }, true, key);
      keys.delete(fingerprint);
      return result;
    } catch (failure) {
      if (failure instanceof ApiError && failure.status >= 400 && failure.status < 500 && failure.status !== 408) keys.delete(fingerprint);
      throw failure;
    }
  }
  async function user(roles = readerRoles): Promise<UserProfile> {
    const profile = await transport.auth.me();
    if (!profile.roles.some((role) => roles.includes(role))) error(403, "Your role cannot perform this operation.");
    return profile;
  }
  function find(commentId: string) { return records.find((record) => record.id === commentId) || error(404, "Comment not found."); }
  function checkVersion(actual: number, expected: number) { if (actual !== expected) error(409, "The resource changed. Refresh and retry."); }
  function privilege(userId: string): Privilege {
    const value = privileges.get(userId) || { userId, status: "allowed", suspendedUntil: null, reason: null, moderatorId: null, updatedAt: now(), version: -1 };
    return { ...value, status: value.suspendedUntil && Date.parse(value.suspendedUntil) <= Date.now() ? "allowed" : value.status };
  }
  function policy(articleId: string) {
    const local = articleSettings.get(articleId);
    return { enabled: settings.enabled && local?.enabledOverride !== false, requireApproval: local?.requireApprovalOverride ?? settings.requireApproval };
  }
  function canWrite(articleId: string, userId: string) {
    if (!policy(articleId).enabled) error(409, "Comments are disabled for this article.");
    if (privilege(userId).status === "suspended") error(403, "Commenting privilege is suspended.");
  }
  function commentState(articleId: string) { return policy(articleId).requireApproval ? "pending" : "approved"; }
  function plain(body: string) { if (!body.trim() || body.length > 5000 || /[<>]/.test(body)) error(400, "Use plain text, up to 5000 characters."); return body.trim(); }
  function reason(value: string) { if (!value.trim() || value.length > 2000) error(400, "A reason of up to 2000 characters is required."); }

  return {
    async discussion(articleId: string): Promise<Discussion> {
      if (transport.mode !== "fake") return request(`/api/v1/articles/${articleId}/discussion`);
      let profile: UserProfile | null = null;
      try { profile = await transport.auth.me(); } catch (failure) { if (!(failure instanceof ApiError && failure.status === 401)) throw failure; }
      const currentPolicy = policy(articleId);
      const eligible = !!profile?.roles.some((role) => readerRoles.includes(role));
      const status = profile ? privilege(profile.id) : null;
      const visible = new Map<string, ModerationItem>();
      for (const record of records.filter((record) => record.articleId === articleId)) {
        const parent = record.parentId ? records.find((item) => item.id === record.parentId) : null;
        if (record.state === "approved" && (!record.parentId || parent?.state === "approved" || parent?.state === "deleted")) {
          if (parent) visible.set(parent.id, parent);
          visible.set(record.id, record);
        }
        if (record.authorId === profile?.id) visible.set(record.id, record);
      }
      return { comments: currentPolicy.enabled ? structuredClone([...visible.values()]) : [], viewerId: profile?.id || null, authenticated: !!profile, eligible, ...currentPolicy, editingWindowMinutes: settings.editingWindowMinutes, maximumReplyDepth: 1, privilegeStatus: status?.status || "allowed", suspendedUntil: status?.suspendedUntil || null, canComment: currentPolicy.enabled && eligible && status?.status !== "suspended" };
    },
    async submit(articleId: string, body: string, parentId: string | null = null): Promise<{ id: string }> {
      if (transport.mode !== "fake") return request(`/api/v1/articles/${articleId}/comments`, "POST", { body, parentId });
      const profile = await user(); canWrite(articleId, profile.id);
      if (parentId) { const parent = find(parentId); if (parent.articleId !== articleId || parent.parentId || parent.deleted || parent.state !== "approved") error(400, "Replies require a visible top-level comment."); }
      const record: ModerationItem = { ...records[0], id: crypto.randomUUID(), articleId, authorId: profile.id, authorName: profile.displayName, body: plain(body), parentId, state: commentState(articleId), deleted: false, deletedByAuthor: false, version: 0, createdAt: now(), editedAt: null, openReports: 0 };
      records.push(record); return { id: record.id };
    },
    async edit(comment: CommunityComment, body: string): Promise<void> {
      if (transport.mode !== "fake") return request(`/api/v1/comments/${comment.id}`, "PUT", { body, expectedVersion: comment.version });
      const profile = await user(); const record = find(comment.id);
      if (record.authorId !== profile.id) error(403, "Only the author can edit this comment.");
      checkVersion(record.version, comment.version); canWrite(record.articleId, profile.id);
      if (record.deleted || Date.now() >= Date.parse(record.createdAt) + settings.editingWindowMinutes * 60000) error(409, "The editing window has expired.");
      Object.assign(record, { body: plain(body), state: commentState(record.articleId), editedAt: now(), version: record.version + 1 });
    },
    async deleteOwn(comment: CommunityComment): Promise<void> {
      if (transport.mode !== "fake") return request(`/api/v1/comments/${comment.id}?expectedVersion=${comment.version}`, "DELETE");
      const profile = await user(); const record = find(comment.id);
      if (record.authorId !== profile.id) error(403, "Only the author can delete this comment.");
      checkVersion(record.version, comment.version);
      Object.assign(record, { body: "[deleted]", deleted: true, deletedByAuthor: true, version: record.version + 1 });
    },
    async report(commentId: string, reportReason: string, details: string): Promise<{ id: string }> {
      if (transport.mode !== "fake") return request(`/api/v1/comments/${commentId}/reports`, "POST", { reason: reportReason, details });
      const profile = await user(); const record = find(commentId);
      if (record.authorId === profile.id || record.deleted || record.state !== "approved" || !policy(record.articleId).enabled) error(409, "Only another reader's visible comment can be reported.");
      if (record.parentId && !["approved", "deleted"].includes(find(record.parentId).state)) error(409, "Only visible comments can be reported.");
      if (reports.some((report) => report.commentId === commentId && report.reporterId === profile.id)) error(409, "You already reported this comment.");
      const report = { id: crypto.randomUUID(), commentId, reporterId: profile.id, reason: reportReason, details, status: "open", createdAt: now() };
      reports.push(report); record.openReports++;
      if (record.openReports >= settings.reportEscalationThreshold) { record.state = "pending"; record.version++; }
      return { id: report.id };
    },
    async queue(state = "pending", page = 0): Promise<Page<ModerationItem>> {
      if (transport.mode !== "fake") return request(`/api/v1/admin/comments?${new URLSearchParams({ ...(state ? { state } : {}), page: String(page), size: "20" })}`);
      await user(staffRoles); return structuredClone(paged(records.filter((item) => !state || item.state === state), page));
    },
    async detail(commentId: string): Promise<ModerationItem> {
      if (transport.mode !== "fake") return request(`/api/v1/admin/comments/${commentId}`);
      await user(staffRoles); return structuredClone(find(commentId));
    },
    async reports(): Promise<CommentReport[]> {
      if (transport.mode !== "fake") return request("/api/v1/admin/comment-reports?limit=100");
      await user(staffRoles); return structuredClone(reports.filter((report) => report.status === "open"));
    },
    async history(commentId: string): Promise<ModerationHistory[]> {
      if (transport.mode !== "fake") return request(`/api/v1/admin/comments/${commentId}/history`);
      await user(staffRoles); return structuredClone(histories.get(commentId) || []);
    },
    async moderate(comment: ModerationItem, decision: Decision, why: string): Promise<void> {
      if (transport.mode !== "fake") return request(`/api/v1/admin/comments/${comment.id}/moderate`, "POST", { decision, reason: why, expectedVersion: comment.version });
      const profile = await user(staffRoles); reason(why); const record = find(comment.id); checkVersion(record.version, comment.version);
      if (record.deleted) error(409, "Deleted comments cannot be moderated again.");
      histories.set(record.id, [{ id: crypto.randomUUID(), moderatorId: profile.id, previousState: record.state, action: decision, reason: why, createdAt: now() }, ...(histories.get(record.id) || [])]);
      Object.assign(record, { state: decision, version: record.version + 1, openReports: 0, ...(decision === "deleted" ? { deleted: true, body: "[deleted by moderator]" } : {}) });
      reports.filter((report) => report.commentId === record.id).forEach((report) => { report.status = "resolved"; });
    },
    async globalSettings(): Promise<CommentSettings> {
      if (transport.mode !== "fake") return request("/api/v1/admin/comment-settings");
      await user(staffRoles); return { ...settings };
    },
    async updateGlobalSettings(value: CommentSettings): Promise<void> {
      const { version, enabled, requireApproval, editingWindowMinutes, reviewSpamThreshold, rejectSpamThreshold, reportEscalationThreshold } = value;
      const input = { enabled, requireApproval, editingWindowMinutes, reviewSpamThreshold, rejectSpamThreshold, reportEscalationThreshold };
      if (transport.mode !== "fake") return request("/api/v1/admin/comment-settings", "PUT", { ...input, expectedVersion: version });
      await user(["ADMINISTRATOR"]); checkVersion(settings.version, version);
      if (value.reviewSpamThreshold > value.rejectSpamThreshold) error(400, "Review threshold must not exceed rejection threshold.");
      settings = { ...value, version: version + 1, updatedAt: now() };
    },
    async articles(page = 0): Promise<Page<CommentArticle>> {
      if (transport.mode !== "fake") return request(`/api/v1/admin/comment-articles?page=${page}&size=20`);
      await user(staffRoles); return paged([fixtureArticle], page);
    },
    async articleSettings(articleId: string): Promise<ArticleCommentSettings> {
      if (transport.mode !== "fake") return request(`/api/v1/admin/articles/${articleId}/comment-settings`);
      await user(staffRoles); return { ...(articleSettings.get(articleId) || { articleId, enabledOverride: null, requireApprovalOverride: null, version: -1, updatedAt: now() }) };
    },
    async updateArticleSettings(value: ArticleCommentSettings): Promise<void> {
      if (transport.mode !== "fake") return request(`/api/v1/admin/articles/${value.articleId}/comment-settings`, "PUT", { enabledOverride: value.enabledOverride, requireApprovalOverride: value.requireApprovalOverride, expectedVersion: value.version });
      await user(["ADMINISTRATOR"]); checkVersion(articleSettings.get(value.articleId)?.version ?? -1, value.version);
      articleSettings.set(value.articleId, { ...value, version: value.version + 1, updatedAt: now() });
    },
    async users(query: string): Promise<CommentingUser[]> {
      if (transport.mode !== "fake") return request(`/api/v1/admin/commenting-users?${new URLSearchParams({ query, limit: "20" })}`);
      await user(staffRoles);
      if (query.trim().length < 2 || query.length > 100) error(400, "Search using 2–100 characters.");
      return fixtureUsers.filter((profile) => profile.displayName.toLowerCase().includes(query.toLowerCase())).slice(0, 20);
    },
    async user(userId: string): Promise<CommentingUser> {
      if (transport.mode !== "fake") return request(`/api/v1/admin/commenting-users/${userId}`);
      await user(staffRoles); return fixtureUsers.find((profile) => profile.id === userId) || error(404, "Account not found.");
    },
    async privilege(userId: string): Promise<Privilege> {
      if (transport.mode !== "fake") return request(`/api/v1/admin/commenting-privileges/${userId}`);
      await user(staffRoles); return privilege(userId);
    },
    async privilegeHistory(userId: string): Promise<PrivilegeHistory[]> {
      if (transport.mode !== "fake") return request(`/api/v1/admin/commenting-privileges/${userId}/history`);
      await user(staffRoles); return structuredClone(privilegeHistories.get(userId) || []);
    },
    async setPrivilege(value: Privilege, action: "suspend" | "restore", why: string, until: string | null): Promise<void> {
      if (transport.mode !== "fake") return request(`/api/v1/admin/commenting-privileges/${value.userId}/${action}`, "POST", { reason: why, expectedVersion: value.version, ...(action === "suspend" ? { until } : {}) });
      const profile = await user(staffRoles); reason(why); checkVersion(privilege(value.userId).version, value.version);
      if (until && Date.parse(until) <= Date.now()) error(400, "Suspension end must be in the future.");
      privileges.set(value.userId, { ...value, status: action === "suspend" ? "suspended" : "allowed", reason: why, suspendedUntil: action === "suspend" ? until : null, moderatorId: profile.id, version: value.version + 1, updatedAt: now() });
      privilegeHistories.set(value.userId, [{ id: crypto.randomUUID(), action: action === "suspend" ? "suspended" : "restored", reason: why, suspendedUntil: until, moderatorId: profile.id, createdAt: now() }, ...(privilegeHistories.get(value.userId) || [])]);
    },
  };
}

export const moderationApi = createModerationApi();
