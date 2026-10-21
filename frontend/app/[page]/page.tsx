import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { AuthenticatedArea } from "@/components/authenticated-area";
import { AuthForm } from "@/components/forms";
import { AccountSettings } from "@/components/account/account-settings";
import { PasswordResetCompletion, VerifyEmail } from "@/components/account/token-completion";
import { alternates } from "@/lib/i18n";
import { requestLocale } from "@/lib/i18n/server";
import { publicationPolicies } from "@/lib/publication-policies";

export const dynamic = "force-dynamic";
const authPages = { "sign-in": "sign-in", register: "register", "password-reset": "reset" } as const;
const authTitles = { "sign-in": "session.signIn", register: "auth.createAccount", "password-reset": "reset.submit" } as const;
type Props = { params: Promise<{ page: string }>; searchParams: Promise<{ next?: string; token?: string }> };

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const page = (await params).page;
  const { locale, prefix, t } = await requestLocale();
  const legalTitle = ["privacy", "terms", "editorial", "corrections"].includes(page) ? publicationPolicies(process.env, locale).pages[page].title : undefined;
  const authTitle = Object.hasOwn(authTitles, page) ? t(authTitles[page as keyof typeof authTitles]) : undefined;
  const title = legalTitle || authTitle || (page === "profile" ? t("meta.profile") : page === "verify-email" ? t("verify.title") : page);
  return { title, alternates: alternates(prefix, `/${page}`), robots: page === "profile" || page === "verify-email" || page in authPages ? { index: false, follow: false } : undefined };
}
export default async function UtilityPage({ params, searchParams }: Props) {
  const page = (await params).page;
  const query = await searchParams;
  if (page === "verify-email") return <div className="auth-wrap shell"><VerifyEmail token={query.token || ""} /></div>;
  if (page === "password-reset" && query.token !== undefined) return <div className="auth-wrap shell"><PasswordResetCompletion token={query.token} /></div>;
  if (page in authPages) return <div className="auth-wrap shell"><AuthForm kind={authPages[page as keyof typeof authPages]} nextPath={(await searchParams).next} /></div>;
  if (page === "profile") return <AuthenticatedArea><AccountSettings /></AuthenticatedArea>;
  const { locale, t } = await requestLocale();
  const policies = publicationPolicies(process.env, locale);
  const legal = Object.hasOwn(policies.pages, page) ? policies.pages[page] : undefined;
  if (legal) return <article className="legal shell"><p className="eyebrow">{policies.publisher}</p><h1>{legal.title}</h1>
    {!policies.approved && <p className="notice">{t("policy.draft")}</p>}
    <p>{t("policy.version", { version: policies.version })}{policies.effectiveDate && <> · {t("policy.effective")} <time dateTime={policies.effectiveDate}>{policies.effectiveDate}</time></>}</p>
    {legal.sections.map(({ heading, paragraphs }) => <section key={heading}><h2>{heading}</h2>{paragraphs.map((copy, index) => <p key={index}>{copy}</p>)}</section>)}</article>;
  notFound();
}
