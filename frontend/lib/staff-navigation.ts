const editors = ["EDITOR", "ADMINISTRATOR"];
const administrators = ["ADMINISTRATOR"];
const moderators = ["MODERATOR", "ADMINISTRATOR"];
export const staffSections = [
  { slug: "dashboard", label: "Dashboard", roles: ["MODERATOR", ...editors] },
  { slug: "editor", label: "Articles", roles: editors },
  { slug: "candidates", label: "Candidates", roles: editors },
  { slug: "schedules", label: "Schedules", roles: editors },
  { slug: "handles", label: "X accounts", roles: administrators },
  { slug: "comments", label: "Comments", roles: moderators },
  { slug: "newsletter", label: "Newsletter", roles: administrators },
  { slug: "users", label: "Users and roles", roles: administrators },
  { slug: "ai", label: "AI configuration", roles: administrators },
  { slug: "audit", label: "Audit logs", roles: administrators },
  { slug: "events", label: "Failed events", roles: administrators },
  { slug: "operations", label: "Operations", roles: administrators }
] as const;

export function permittedStaffSections(roles: readonly string[]) {
  return staffSections.filter((section) => section.roles.some((role) => roles.includes(role)));
}

export function staffWorkspaceLinks(roles: readonly string[]) {
  const permitted = permittedStaffSections(roles);
  return [
    { section: "users", label: "Administration", href: "/admin" },
    { section: "editor", label: "Editor", href: "/admin/editor" },
    { section: "comments", label: "Moderation", href: "/admin/comments" }
  ].filter((link) => permitted.some((section) => section.slug === link.section));
}
