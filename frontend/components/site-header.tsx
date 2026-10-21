"use client";

import Link, { useLocale } from "./locale";
import { usePathname, useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { useEffect, useState } from "react";
import { facetPath, facetTitle } from "@/lib/content";
import { publicApi } from "@/lib/public-api";
import { defaultLocale, localePrefix, localizePath, splitLocale } from "@/lib/i18n";
import { tx } from "@/lib/i18n/staff";
import { staffWorkspaceLinks } from "@/lib/staff-navigation";
import { useSession } from "./use-session";
import { useAction } from "./admin/shared";

export function SiteHeader() {
  // Loaded in the browser: fetching in the layout would stream every page, and scripts that
  // arrive with streamed content are added without the page's CSP nonce.
  const [topicLinks, setTopicLinks] = useState<string[]>([]);
  useEffect(() => {
    let current = true;
    publicApi.topics().then((topics) => { if (current) setTopicLinks(topics.slice(0, 10).map((topic) => topic.value)); },
      () => { /* Navigation stays usable without the topic shortcuts. */ });
    return () => { current = false; };
  }, []);
  const { status, user, refresh } = useSession();
  const router = useRouter();
  const pathname = usePathname();
  const action = useAction();
  const { locale, t, path } = useLocale();
  const workspaces = staffWorkspaceLinks(user?.roles || []);
  const other = locale === defaultLocale ? "en" : defaultLocale;
  const otherHref = localizePath(localePrefix(other), splitLocale(pathname || "/").pathname);
  return <header className="site-header">
    <div className="shell header-inner">
      <Link className="wordmark" href="/" aria-label={t("site.home")}>NSANGUSA</Link>
      <nav aria-label={t("nav.primary")}>
        <ul className="nav-list">
          <li><Link href="/latest" prefetch={false}>{t("nav.latest")}</Link></li>
          <li><Link href="/topics">{t("nav.topics")}</Link></li>
          <li><Link href="/newsletter">{t("nav.newsletter")}</Link></li>
          <li><Link href="/search">{t("nav.search")}</Link></li>
          <li><Link href="/profile">{t("nav.account")}</Link></li>
        </ul>
      </nav>
      <div className="header-account">
        <a className="quiet-link language-switch" href={otherHref} hrefLang={other} lang={other} aria-label={t("nav.switchTo")} onClick={(event) => {
          // Keep the current query, such as a search term, when changing language.
          if (window.location.search) { event.preventDefault(); window.location.assign(otherHref + window.location.search); }
        }}>{t("nav.switchShort")}</a>
        {status === "checking" && <span role="status">{t("session.checking")}</span>}
        {(status === "anonymous" || status === "signed-out") && <Link className="quiet-link" href="/sign-in">{t("session.signIn")}</Link>}
        {status === "authenticated" && <>
          <Link href="/profile" aria-label={t("session.account", { name: user.displayName })}>{user.displayName}</Link>
          <button aria-label={t("session.signOutLabel")} disabled={action.busy} onClick={async () => {
            if (await action.run(() => api.auth.logout(), t("session.signedOut"))) {
              router.replace(path("/sign-in")); router.refresh();
            }
          }}>{t("session.signOut")}</button>
        </>}
        {status === "error" && <><span role="status">{t("session.unavailable")}</span><button onClick={refresh}>{t("session.retry")}</button></>}
        {action.error && <span role="alert">{action.error}</span>}
      </div>
    </div>
    {workspaces.length > 0 && <nav className="workspace-bar shell" aria-label={t("nav.workspacesLabel")}>
      <span>{t("nav.workspaces")}</span>
      {workspaces.map(({ label, href }) => <Link key={href} href={href} prefetch={false} aria-current={pathname === path(href) ? "page" : undefined}>{tx(label)}</Link>)}
    </nav>}
    <nav className="topic-bar shell" aria-label={t("nav.topics")}>{topicLinks.map((topic) => <Link key={topic} href={facetPath("topics", topic)}>{facetTitle(topic)}</Link>)}</nav>
  </header>;
}
