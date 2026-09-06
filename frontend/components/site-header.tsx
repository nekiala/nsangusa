import Link from "next/link";
import { topics } from "@/lib/content";

export function SiteHeader() {
  return <header className="site-header">
    <div className="shell header-inner">
      <Link className="wordmark" href="/" aria-label="Nsangusa home">NSANGUSA</Link>
      <nav aria-label="Primary navigation">
        <ul className="nav-list">
          <li><Link href="/latest">Latest</Link></li>
          <li><Link href="/topics">Topics</Link></li>
          <li><Link href="/newsletter">Newsletter</Link></li>
          <li><Link href="/search">Search</Link></li>
        </ul>
      </nav>
      <Link className="quiet-link" href="/sign-in">Sign in</Link>
    </div>
    <nav className="topic-bar shell" aria-label="Topics">{topics.map((topic) => <Link key={topic} href={`/topics/${topic.toLowerCase()}`}>{topic}</Link>)}</nav>
  </header>;
}
