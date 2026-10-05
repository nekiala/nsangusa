"use client";

// Self-contained on purpose: the boundary must render when the app's own modules have failed, and
// importing them would add a lazily loaded script that carries no CSP nonce.
const wording = {
  en: { eyebrow: "A temporary problem", title: "We could not load this page.", body: "Please try again. If it persists, return to the home page.", retry: "Try again" },
  fr: { eyebrow: "Un problème temporaire", title: "Impossible de charger cette page.", body: "Veuillez réessayer. Si le problème persiste, revenez à l’accueil.", retry: "Réessayer" }
};

export default function ErrorPage({ reset }: { error: Error & { digest?: string }; reset: () => void }) {
  const text = wording[typeof document !== "undefined" && document.documentElement.lang === "fr" ? "fr" : "en"];
  return <section className="empty shell"><p className="eyebrow">{text.eyebrow}</p><h1>{text.title}</h1><p>{text.body}</p><button onClick={reset}>{text.retry}</button></section>;
}
