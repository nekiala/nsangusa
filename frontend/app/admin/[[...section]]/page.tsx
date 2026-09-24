import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { AdminWorkspace } from "@/components/admin-workspace";
import { AuthenticatedArea } from "@/components/authenticated-area";
import { StaffNavigation } from "@/components/admin/navigation";
import { staffSections } from "@/lib/staff-navigation";

type Props = { params: Promise<{ section?: string[] }> };
export const metadata: Metadata = { title: "Staff workspace", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

export default async function AdminPage({ params }: Props) {
  const segments = (await params).section || [];
  const section = segments[0] || "dashboard";
  const selected = staffSections.find(({ slug }) => slug === section);
  if (!selected || segments.length > 2 || (segments.length === 2 && section !== "editor")) notFound();
  return <AuthenticatedArea allowedRoles={selected.roles}><section className="admin-layout"><div className="shell admin-shell"><StaffNavigation section={section} /><AdminWorkspace key={segments.join("/")} section={section} id={segments[1]} /></div></section></AuthenticatedArea>;
}
