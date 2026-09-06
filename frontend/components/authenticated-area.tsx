"use client";

import { useEffect, useState, type ReactNode } from "react";
import { usePathname, useRouter } from "next/navigation";
import { ApiError, api } from "@/lib/api";

type AccessState = "checking" | "allowed" | "denied" | "error";

export function AuthenticatedArea({ children, staff = false }: { children: ReactNode; staff?: boolean }) {
  const pathname = usePathname();
  const router = useRouter();
  const [state, setState] = useState<AccessState>("checking");

  useEffect(() => {
    let active = true;
    api.auth.me().then((user) => {
      if (!active) return;
      const hasStaffRole = user.roles.some((role) => role === "EDITOR" || role === "ADMINISTRATOR");
      setState(staff && !hasStaffRole ? "denied" : "allowed");
    }).catch((error: unknown) => {
      if (!active) return;
      if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
        router.replace(`/sign-in?next=${encodeURIComponent(pathname)}`);
        return;
      }
      setState("error");
    });
    return () => { active = false; };
  }, [pathname, router, staff]);

  if (state === "checking") return <section className="empty shell" aria-live="polite"><p>Checking account access…</p></section>;
  if (state === "denied") return <section className="empty shell"><p className="eyebrow">Access restricted</p><h1>Staff access is required.</h1><p>Your account does not have permission to use this workspace.</p></section>;
  if (state === "error") return <section className="empty shell" role="alert"><p className="eyebrow">Account service unavailable</p><h1>We could not verify your access.</h1><p>Please try again in a moment.</p></section>;
  return children;
}
