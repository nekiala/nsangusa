import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { AuthenticatedArea } from "@/components/authenticated-area";
import { AuthForm } from "@/components/forms";
import { AccountSettings } from "@/components/account/account-settings";
import { PasswordResetCompletion, VerifyEmail } from "@/components/account/token-completion";
import { publicationPolicies } from "@/lib/publication-policies";

export const dynamic = "force-dynamic";
const authPages = { "sign-in": "sign-in", register: "register", "password-reset": "reset" } as const;
const authTitles = { "sign-in": "Sign in", register: "Create an account", "password-reset": "Reset password" } as const;
type Props = { params: Promise<{ page: string }>; searchParams: Promise<{ next?: string; token?: string }> };

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const page = (await params).page;
  const legalTitle = ["privacy", "terms", "editorial", "corrections"].includes(page) ? publicationPolicies().pages[page].title : undefined;
  const title = legalTitle || authTitles[page as keyof typeof authTitles] || (page === "profile" ? "Your profile" : page === "verify-email" ? "Verify your email" : page);
  return { title, alternates: { canonical: `/${page}` }, robots: page === "profile" || page === "verify-email" || page in authPages ? { index: false, follow: false } : undefined };
}
export default async function UtilityPage({ params, searchParams }: Props) {
  const page = (await params).page;
  const query = await searchParams;
  if (page === "verify-email") return <div className="auth-wrap shell"><VerifyEmail token={query.token || ""} /></div>;
  if (page === "password-reset" && query.token !== undefined) return <div className="auth-wrap shell"><PasswordResetCompletion token={query.token} /></div>;
  if (page in authPages) return <div className="auth-wrap shell"><AuthForm kind={authPages[page as keyof typeof authPages]} nextPath={(await searchParams).next} /></div>;
  if (page === "profile") return <AuthenticatedArea><AccountSettings /></AuthenticatedArea>;
  const policies = publicationPolicies();
  const legal = Object.hasOwn(policies.pages, page) ? policies.pages[page] : undefined;
  if (legal) return <article className="legal shell"><p className="eyebrow">{policies.publisher}</p><h1>{legal.title}</h1>
    {!policies.approved && <p className="notice">Draft publication policy. Publisher, editorial and privacy approval is required before public launch.</p>}
    <p>Policy version: {policies.version}{policies.effectiveDate && <> · Effective <time dateTime={policies.effectiveDate}>{policies.effectiveDate}</time></>}</p>
    {legal.sections.map(({ heading, paragraphs }) => <section key={heading}><h2>{heading}</h2>{paragraphs.map((copy, index) => <p key={index}>{copy}</p>)}</section>)}</article>;
  notFound();
}
