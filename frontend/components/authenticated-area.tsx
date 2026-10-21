"use client";

import { createContext, useContext, useEffect, type ReactNode } from "react";
import Link, { useLocale } from "@/components/locale";
import { usePathname, useRouter } from "next/navigation";
import { type UserProfile } from "@/lib/api";
import { splitLocale } from "@/lib/i18n";
import { useSession } from "./use-session";

const UserContext = createContext<UserProfile | null>(null);
export function useAuthenticatedUser() { return useContext(UserContext); }

export function AuthenticatedArea({ children, staff = false, allowedRoles }: { children: ReactNode; staff?: boolean; allowedRoles?: readonly string[] }) {
  const pathname = usePathname();
  const { replace } = useRouter();
  const { status, user } = useSession();
  const { t, path } = useLocale();
  const required = allowedRoles || (staff ? ["EDITOR", "ADMINISTRATOR"] : []);

  useEffect(() => {
    // Explicit sign-out/revocation/deletion actions own their completion destination.
    // The return path is stored without its language; sign-in adds the current one back.
    if (status === "anonymous") replace(path(`/sign-in?next=${encodeURIComponent(splitLocale(pathname).pathname)}`));
  }, [status, pathname, replace, path]);

  if (status === "error") return <section className="empty shell" role="alert"><p className="eyebrow">{t("access.unavailableEyebrow")}</p><h1>{t("access.unverified")}</h1><p>{t("access.tryLater")}</p></section>;
  if (status === "signed-out") return <section className="empty shell"><h1>{t("access.signedOut")}</h1><Link href="/sign-in">{t("access.signInAgain")}</Link></section>;
  if (status !== "authenticated") return <section className="empty shell" aria-live="polite"><p>{t("access.checking")}</p></section>;
  if (required.length && !required.some((role) => user.roles.includes(role))) return <section className="empty shell"><p className="eyebrow">{t("access.restricted")}</p><h1>{t("access.staffRequired")}</h1><p>{t("access.noPermission")}</p></section>;
  return <UserContext value={user}>{children}</UserContext>;
}
