"use client";
export default function ErrorPage({ reset }: { error: Error & { digest?: string }; reset: () => void }) { return <section className="empty shell"><p className="eyebrow">A temporary problem</p><h1>We could not load this page.</h1><p>Please try again. If it persists, return to the home page.</p><button onClick={reset}>Try again</button></section>; }
