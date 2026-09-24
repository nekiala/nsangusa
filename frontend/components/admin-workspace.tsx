"use client";

import { ArticleEditor, ArticleQueue } from "./admin/article-editor";
import { CandidateQueue } from "./admin/candidates";
import { XAccounts } from "./admin/x-accounts";
import { PublicationSchedules } from "./admin/schedules";
import { AuditLogs, EditorialDashboard, FailedEvents, OperationsHealth } from "./admin/operations";
import { ModerationWorkspace } from "./admin/moderation";
import { UsersWorkspace } from "./admin/users";
import { NewsletterWorkspace } from "./admin/newsletter";
import { AiConfigurationWorkspace } from "./admin/ai-configuration";
import { useAuthenticatedUser } from "./authenticated-area";

export function AdminWorkspace({ section, id }: { section: string; id?: string }) {
  const user = useAuthenticatedUser();
  if (section === "comments") return <ModerationWorkspace roles={user?.roles || []} />;
  if (section === "users") return <UsersWorkspace />;
  if (section === "newsletter") return <NewsletterWorkspace />;
  if (section === "ai") return <AiConfigurationWorkspace />;
  if (section === "handles") return <XAccounts />;
  if (section === "candidates") return <CandidateQueue />;
  if (section === "schedules") return <PublicationSchedules />;
  if (section === "editor") return id ? <ArticleEditor key={id} id={id} /> : <ArticleQueue />;
  if (section === "audit") return <AuditLogs />;
  if (section === "events") return <FailedEvents />;
  if (section === "operations") return <OperationsHealth />;
  return <EditorialDashboard />;
}
