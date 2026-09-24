"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { topics } from "@/lib/content";
import { staffWorkspaceLinks } from "@/lib/staff-navigation";
import { useSession } from "./use-session";
import { useAction } from "./admin/shared";

export function SiteHeader() {
  const { status, user, refresh } = useSession();
  const router = useRouter();
  const pathname = usePathname();
  const action = useAction();
  const workspaces = staffWorkspaceLinks(user?.roles || []);
  return <header className="site-header">
    <div className="shell header-inner">
      <Link className="wordmark" href="/" aria-label="Nsangusa home">NSANGUSA</Link>
      <nav aria-label="Primary navigation">
        <ul className="nav-list">
          <li><Link href="/latest" prefetch={false}>Latest</Link></li>
          <li><Link href="/topics">Topics</Link></li>
          <li><Link href="/newsletter">Newsletter</Link></li>
          <li><Link href="/search">Search</Link></li>
          <li><Link href="/profile">Account</Link></li>
        </ul>
      </nav>
      <div className="header-account">
        {status === "checking" && <span role="status">Checking sign-in...</span>}
        {(status === "anonymous" || status === "signed-out") && <Link className="quiet-link" href="/sign-in">Sign in</Link>}
        {status === "authenticated" && <>
          <Link href="/profile" aria-label={`Your account: ${user.displayName}`}>{user.displayName}</Link>
          <button aria-label="Sign out of your account" disabled={action.busy} onClick={async () => {
            if (await action.run(() => api.auth.logout(), "You are signed out.")) {
              router.replace("/sign-in"); router.refresh();
            }
          }}>Sign out</button>
        </>}
        {status === "error" && <><span role="status">Account service unavailable.</span><button onClick={refresh}>Retry account</button></>}
        {action.error && <span role="alert">{action.error}</span>}
      </div>
    </div>
    {workspaces.length > 0 && <nav className="workspace-bar shell" aria-label="Your workspaces">
      <span>Workspaces</span>
      {workspaces.map(({ label, href }) => <Link key={href} href={href} prefetch={false} aria-current={pathname === href ? "page" : undefined}>{label}</Link>)}
    </nav>}
    <nav className="topic-bar shell" aria-label="Topics">{topics.map((topic) => <Link key={topic} href={`/topics/${topic.toLowerCase()}`}>{topic}</Link>)}</nav>
  </header>;
}
