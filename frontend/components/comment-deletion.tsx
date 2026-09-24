"use client";

import { useEffect, useRef } from "react";

export function CommentDeletion({ busy, cancel, confirm }: { busy: boolean; cancel: () => void; confirm: () => void }) {
  const safeAction = useRef<HTMLButtonElement>(null);
  useEffect(() => { safeAction.current?.focus(); }, []);
  return <div role="group" aria-label="Confirm comment deletion" onKeyDown={(event) => {
    if (event.key === "Escape" && !busy) { event.preventDefault(); cancel(); }
  }}>
    <p>Delete your comment? Its place in the thread is retained, but its text cannot be restored.</p>
    <button disabled={busy} onClick={confirm}>Confirm delete</button>
    <button ref={safeAction} disabled={busy} onClick={cancel}>Cancel deletion</button>
  </div>;
}
