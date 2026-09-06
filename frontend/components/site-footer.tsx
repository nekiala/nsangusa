import Link from "next/link";
import { EmailForm } from "@/components/forms";

export function SiteFooter() {
  return <footer className="site-footer"><div className="shell footer-grid">
    <div><p className="wordmark">NSANGUSA</p><p>Independent reporting and essays for a more attentive public life.</p></div>
    <EmailForm compact />
    <nav aria-label="Footer navigation"><Link href="/editorial">Editorial standards</Link><Link href="/corrections">Corrections</Link><Link href="/privacy">Privacy</Link><Link href="/terms">Terms</Link><Link href="/admin">Staff</Link></nav>
  </div><p className="shell copyright">© 2026 Nsangusa. Built to be read slowly.</p></footer>;
}
