import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { AuthenticatedArea } from "@/components/authenticated-area";
import { AuthForm } from "@/components/forms";

const legalCopy: Record<string, { title: string; sections: [string, string][] }> = {
  privacy: { title: "Privacy", sections: [["What we collect", "We collect only the information needed to deliver an account or newsletter you ask for. We do not sell personal data or use advertising trackers."], ["Your choices", "You may update your preferences, unsubscribe, or request deletion of your information at any time."]]},
  terms: { title: "Terms of use", sections: [["Using our work", "Our reporting is provided for personal, non-commercial reading. Please link to our work rather than reproducing it in full."], ["Accounts", "Keep credentials private and contact us if you believe your account has been accessed without permission."]]},
  editorial: { title: "Editorial standards", sections: [["Independence", "Our reporting is guided by evidence, public interest and editorial independence. Funding relationships never determine coverage."], ["Methods", "We seek primary sources, identify uncertainty, and give subjects a fair chance to respond to material claims."]]},
  corrections: { title: "Corrections", sections: [["How we correct", "When we make a material error, we correct it promptly and append a clear note explaining what changed."], ["Report an issue", "Send a concise description and supporting information to corrections@nsangusa.example."]]}
};
const authPages = { "sign-in": "sign-in", register: "register", "password-reset": "reset" } as const;
const authTitles = { "sign-in": "Sign in", register: "Create an account", "password-reset": "Reset password" } as const;
type Props = { params: Promise<{ page: string }>; searchParams: Promise<{ next?: string }> };

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const page = (await params).page;
  const title = legalCopy[page]?.title || authTitles[page as keyof typeof authTitles] || (page === "profile" ? "Your profile" : page);
  return { title, alternates: { canonical: `/${page}` }, robots: page === "profile" || page in authPages ? { index: false, follow: false } : undefined };
}
export default async function UtilityPage({ params, searchParams }: Props) {
  const page = (await params).page;
  if (page in authPages) return <div className="auth-wrap shell"><AuthForm kind={authPages[page as keyof typeof authPages]} nextPath={(await searchParams).next} /></div>;
  if (page === "profile") return <AuthenticatedArea><section className="section shell"><p className="eyebrow">Member area</p><h1>Your profile & preferences</h1><div className="auth-panel"><form><label htmlFor="profile-name">Display name</label><input id="profile-name" defaultValue="Reader" autoComplete="name" readOnly /><label htmlFor="digest">Newsletter frequency</label><select id="digest" defaultValue="Weekly" disabled><option>Weekly</option><option>Monthly</option><option>None</option></select><p className="form-note">Account changes are disabled in this frontend demonstration.</p><Link href="/password-reset">Reset password</Link></form></div></section></AuthenticatedArea>;
  const legal = legalCopy[page];
  if (legal) return <article className="legal shell"><p className="eyebrow">Nsangusa</p><h1>{legal.title}</h1>{legal.sections.map(([heading, copy]) => <section key={heading}><h2>{heading}</h2><p>{copy}</p></section>)}</article>;
  notFound();
}
