"use client";

import { tx } from "@/lib/i18n/staff";
import Link from "@/components/locale";
import { useAuthenticatedUser } from "@/components/authenticated-area";
import { permittedStaffSections } from "@/lib/staff-navigation";
import { api } from "@/lib/api";

export function StaffNavigation({ section }: { section: string }) {
  const user = useAuthenticatedUser();
  return <nav className="admin-nav" aria-label={tx("Staff workspace")}>
    {permittedStaffSections(user?.roles || []).map(({ slug, label }) =>
      <Link key={slug} href={slug === "dashboard" ? "/admin" : `/admin/${slug}`} aria-current={section === slug ? "page" : undefined}>{tx(label)}</Link>)}
    <Link href="/profile">{tx("Your account")}</Link>
    {api.mode === "fake" && <p className="notice">{tx("Explicit fake mode: demonstration data only.")}</p>}
  </nav>;
}
