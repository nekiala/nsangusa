"use client";

import { tx } from "@/lib/i18n/staff";
import Image from "next/image";
import { useCallback, useEffect, useState } from "react";
import { api, type ApiArticle, type ImageGeneration, type ImageVariant } from "@/lib/api";
import { dateLabel, errorNotice, Feedback, useAction, useResource } from "./shared";

export function GenerationImage({ generation, variant = "hero" }: { generation: Pick<ImageGeneration, "id" | "altText">; variant?: ImageVariant }) {
  const [image, setImage] = useState<{ id: string; url: string } | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    let objectUrl: string | undefined;
    api.admin.imageContent(generation.id, variant).then((blob) => {
      if (!active) return;
      objectUrl = URL.createObjectURL(blob);
      setImage({ id: generation.id, url: objectUrl }); setError("");
    }).catch((failure: unknown) => { if (active) setError(errorNotice(failure)); });
    return () => { active = false; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [generation.id, variant]);
  return <>{error ? <p role="alert">Image preview unavailable. {error}</p> : image?.id === generation.id
    ? <Image className="editorial-image" unoptimized src={image.url} width={960} height={540} alt={generation.altText} />
    : <p>{tx("Loading image preview…")}</p>}</>;
}

export function ImageReview({ article, onChange, locked }: { article: ApiArticle; onChange: () => void; locked: boolean }) {
  const load = useCallback(() => api.admin.images(article.id), [article.id]);
  const images = useResource(load);
  const action = useAction();
  const [variant, setVariant] = useState<ImageVariant>("hero");
  const canGenerate = ["DRAFTING", "AWAITING_REVIEW", "APPROVED"].includes(article.state);
  return <section className="review-panel" aria-label={tx("Image review")}>
    <h2>{tx("Image review")}</h2>
    <p>{article.imageApprovalRequired ? tx("An image must be reviewed and approved before article approval.") : article.approvedImageGenerationId ? tx("An approved image is selected. A replacement will not be selected until approved.") : tx("No image is selected for this manual article.")}</p>
    <div className="workflow-actions"><button type="button" disabled={images.loading || action.busy} onClick={() => { images.refresh(); onChange(); }}>{tx("Refresh image review")}</button>
      <label>{tx("Preview variant")} <select value={variant} onChange={(event) => { const value = event.target.value; if (value === "hero" || value === "thumbnail" || value === "social") setVariant(value); }}><option value="hero">{tx("Hero")}</option><option value="thumbnail">{tx("Thumbnail")}</option><option value="social">{tx("Social")}</option></select></label></div>
    <Feedback error={images.error || action.error} status={action.status} />
    {images.data?.length === 0 && <p>No image generations yet. Automatic generation may still be processing; refresh to check.</p>}
    {images.data?.map((generation) => <article key={generation.id} className="review-panel">
      <h3>{generation.id === article.approvedImageGenerationId ? tx("Selected image") : generation.approvedAt ? tx("Previously approved image") : tx("Image awaiting review")}</h3>
      <GenerationImage key={`${generation.id}-${variant}`} generation={generation} variant={variant} />
      <p>{generation.provider === "nsangusa-editorial" && generation.model === "neutral-illustration-v1"
        ? `Original neutral editorial fallback illustration, not AI-generated or a documentary photograph.${generation.approvedAt ? "" : " Explicit approval is required before selection."}`
        : tx("AI-generated illustration, not a documentary photograph.")}</p>
      <dl className="facts"><dt>{tx("Prompt")}</dt><dd className="plain-text">{generation.prompt}</dd><dt>{tx("Alternative text")}</dt><dd>{generation.altText}</dd><dt>{tx("Provider / model")}</dt><dd>{generation.provider} / {generation.model}</dd><dt>{tx("Safety status")}</dt><dd>{generation.safetyStatus}</dd><dt>{tx("Created")}</dt><dd>{dateLabel(generation.createdAt)}</dd><dt>{tx("Approved")}</dt><dd>{dateLabel(generation.approvedAt)}</dd></dl>
      <button type="button" disabled={locked || action.busy || !canGenerate || !generation.objectKey || !!generation.approvedAt} onClick={() => action.run(async () => { await api.admin.approveImage(generation.id); images.refresh(); onChange(); }, tx("Image approval accepted. Refresh review if the selection is still processing."))}>{tx("Approve this image")}</button>
    </article>)}
    <form className="admin-form" onSubmit={(event) => {
      event.preventDefault(); const form = new FormData(event.currentTarget);
      action.run(async () => { await api.admin.regenerateImage(article.id, String(form.get("prompt")), String(form.get("altText"))); images.refresh(); onChange(); }, tx("Image generation requested. Refresh image review to inspect the result; existing approved images are preserved."));
    }}>
      <h3>{tx("Generate a new image")}</h3><p>{tx("This requests the configured image provider. Every new image requires human review.")}</p>
      <label htmlFor="image-prompt">{tx("Image prompt")}</label><textarea id="image-prompt" name="prompt" maxLength={4000} required disabled={!canGenerate || locked} defaultValue={`Editorial illustration for ${article.headline}. Clearly illustrative, not documentary photography.`} />
      <label htmlFor="image-alt">{tx("Image alternative text")}</label><input id="image-alt" name="altText" maxLength={500} required disabled={!canGenerate || locked} defaultValue={article.imageAltText || `Illustration accompanying ${article.headline}`} />
      <button disabled={locked || action.busy || !canGenerate}>{tx("Request image generation")}</button>
    </form>
    <form className="admin-form" onSubmit={(event) => {
      event.preventDefault();
      if (locked || action.busy || !canGenerate) return;
      const form = new FormData(event.currentTarget);
      action.run(async () => { await api.admin.requestFallbackImage(article.id, String(form.get("altText")), String(form.get("reason")), article.version); images.refresh(); onChange(); }, tx("Neutral fallback created for review. Inspect and explicitly approve it before selection; existing approved images are preserved."));
    }}>
      <h3>{tx("Select a neutral fallback illustration")}</h3>
      <p>{tx("This explicitly creates an original newspaper-and-geometric-shapes illustration without calling the AI provider. It does not depict the reported event and will not be selected until an editor reviews and approves it.")}</p>
      <label htmlFor="fallback-image-alt">{tx("Fallback alternative text")}</label><input id="fallback-image-alt" name="altText" maxLength={500} required disabled={!canGenerate || locked || action.busy} defaultValue="Neutral editorial illustration of a newspaper and geometric shapes" />
      <label htmlFor="fallback-image-reason">{tx("Fallback selection reason")}</label><textarea id="fallback-image-reason" name="reason" maxLength={2000} required disabled={!canGenerate || locked || action.busy} />
      <button disabled={locked || action.busy || !canGenerate}>{tx("Create fallback for review")}</button>
    </form>
  </section>;
}
