"use client";

import { useEffect, useRef } from "react";
import { useLocale } from "@/components/locale";

export function CommentDeletion({ busy, cancel, confirm }: { busy: boolean; cancel: () => void; confirm: () => void }) {
  const safeAction = useRef<HTMLButtonElement>(null);
  const { t } = useLocale();
  useEffect(() => { safeAction.current?.focus(); }, []);
  return <div role="group" aria-label={t("comment.deleteLabel")} onKeyDown={(event) => {
    if (event.key === "Escape" && !busy) { event.preventDefault(); cancel(); }
  }}>
    <p>{t("comment.deletePrompt")}</p>
    <button disabled={busy} onClick={confirm}>{t("comment.deleteConfirm")}</button>
    <button ref={safeAction} disabled={busy} onClick={cancel}>{t("comment.deleteCancel")}</button>
  </div>;
}
