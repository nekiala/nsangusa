"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { usePathname } from "next/navigation";
import { ApiError, api, type UserProfile } from "@/lib/api";
import { sessionChangedEvent } from "@/lib/session-events";

type Session = { status: "authenticated"; user: UserProfile }
  | { status: "checking" | "anonymous" | "signed-out" | "error"; user: null };

export function useSession() {
  const pathname = usePathname();
  const [revision, setRevision] = useState(0);
  const [result, setResult] = useState<{ pathname: string | null; session: Session }>();
  const generation = useRef(0);
  const endingAt = useRef<string | null | undefined>(undefined);
  const revalidate = useCallback(() => {
    generation.current++;
    setRevision((value) => value + 1);
  }, []);
  const refresh = useCallback(() => { setResult(undefined); revalidate(); }, [revalidate]);

  useEffect(() => {
    let active = true;
    const requestedGeneration = generation.current;
    const ending = endingAt.current === pathname;
    api.auth.me().then((user) => {
      if (active && generation.current === requestedGeneration) {
        endingAt.current = undefined;
        setResult({ pathname, session: { status: "authenticated", user } });
      }
    }).catch((error: unknown) => {
      if (active && generation.current === requestedGeneration) setResult({ pathname, session: {
        status: error instanceof ApiError && [401, 403].includes(error.status) ? ending ? "signed-out" : "anonymous" : "error",
        user: null
      } });
    });
    return () => { active = false; };
  }, [pathname, revision]);

  useEffect(() => {
    const changed = (event: Event) => {
      if (event instanceof CustomEvent && event.detail?.ending === true) endingAt.current = pathname;
      if (event instanceof CustomEvent && event.detail?.reset === false) revalidate();
      else refresh();
    };
    const restored = (event: PageTransitionEvent) => { if (event.persisted) refresh(); };
    window.addEventListener(sessionChangedEvent, changed);
    window.addEventListener("focus", revalidate);
    window.addEventListener("pageshow", restored);
    return () => {
      window.removeEventListener(sessionChangedEvent, changed);
      window.removeEventListener("focus", revalidate);
      window.removeEventListener("pageshow", restored);
    };
  }, [pathname, refresh, revalidate]);

  const session: Session = result?.pathname === pathname
    ? result.session : { status: "checking", user: null };
  return { ...session, refresh };
}
