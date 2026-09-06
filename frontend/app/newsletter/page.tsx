import type { Metadata } from "next";
import { EmailForm } from "@/components/forms";
export const metadata: Metadata = { title: "Newsletter", alternates: { canonical: "/newsletter" } };
export default function NewsletterPage() { return <section className="section shell"><p className="eyebrow">The weekly letter</p><h1>Keep company with good questions.</h1><p className="intro">A quiet digest of the week’s work, plus notes from our editors. No surveillance pixels, no noise.</p><EmailForm /></section>; }
