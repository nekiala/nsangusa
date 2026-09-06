import type { Metadata } from "next";
import Link from "next/link";
import { AdminWorkspace } from "@/components/admin-workspace";
import { AuthenticatedArea } from "@/components/authenticated-area";

const links = [["dashboard", "Operations"], ["editor", "Articles"], ["handles", "X accounts"], ["comments", "Comments"]] as const;
type Props = { params: Promise<{ section?: string[] }> };
export const metadata: Metadata = { title: "Staff workspace", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

export default async function AdminPage({ params }: Props) {
  const requested = (await params).section?.[0] || "dashboard";
  const section = links.some(([slug]) => slug === requested) ? requested : "dashboard";
  return <AuthenticatedArea staff><section className="admin-layout"><div className="shell admin-shell"><nav className="admin-nav" aria-label="Staff workspace">{links.map(([slug, label]) => <Link key={slug} href={slug === "dashboard" ? "/admin" : `/admin/${slug}`} aria-current={section === slug ? "page" : undefined}>{label}</Link>)}</nav><AdminWorkspace section={section} /></div></section></AuthenticatedArea>;
}
