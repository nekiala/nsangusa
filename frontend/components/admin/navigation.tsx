"use client";

import Link from "next/link";
import { useAuthenticatedUser } from "@/components/authenticated-area";
import { permittedStaffSections } from "@/lib/staff-navigation";
import { api } from "@/lib/api";

export function StaffNavigation({ section }: { section: string }) {
  const user = useAuthenticatedUser();
  return <nav className="admin-nav" aria-label="Staff workspace">
    {permittedStaffSections(user?.roles || []).map(({ slug, label }) =>
      <Link key={slug} href={slug === "dashboard" ? "/admin" : `/admin/${slug}`} aria-current={section === slug ? "page" : undefined}>{label}</Link>)}
    <Link href="/profile">Your account</Link>
    {api.mode === "fake" && <p className="notice">Explicit fake mode: demonstration data only.</p>}
  </nav>;
}
