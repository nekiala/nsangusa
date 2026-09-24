"use client";

import { createContext, useContext, useEffect, type ReactNode } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { type UserProfile } from "@/lib/api";
import { useSession } from "./use-session";

const UserContext = createContext<UserProfile | null>(null);
export function useAuthenticatedUser() { return useContext(UserContext); }

export function AuthenticatedArea({ children, staff = false, allowedRoles }: { children: ReactNode; staff?: boolean; allowedRoles?: readonly string[] }) {
  const pathname = usePathname();
  const { replace } = useRouter();
  const { status, user } = useSession();
  const required = allowedRoles || (staff ? ["EDITOR", "ADMINISTRATOR"] : []);

  useEffect(() => {
    // Explicit sign-out/revocation/deletion actions own their completion destination.
    if (status === "anonymous") replace(`/sign-in?next=${encodeURIComponent(pathname)}`);
  }, [status, pathname, replace]);

  if (status === "error") return <section className="empty shell" role="alert"><p className="eyebrow">Account service unavailable</p><h1>We could not verify your access.</h1><p>Please try again in a moment.</p></section>;
  if (status === "signed-out") return <section className="empty shell"><h1>You are signed out.</h1><Link href="/sign-in">Sign in again</Link></section>;
  if (status !== "authenticated") return <section className="empty shell" aria-live="polite"><p>Checking account access…</p></section>;
  if (required.length && !required.some((role) => user.roles.includes(role))) return <section className="empty shell"><p className="eyebrow">Access restricted</p><h1>Staff access is required.</h1><p>Your account does not have permission to use this workspace.</p></section>;
  return <UserContext value={user}>{children}</UserContext>;
}
