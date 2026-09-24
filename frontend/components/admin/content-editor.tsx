"use client";

import { useId, useState } from "react";
import { MAX_CONTENT_BLOCKS, type ArticleBlock, type ArticleContent } from "@/lib/article-content";

const types: { type: ArticleBlock["type"]; label: string }[] = [
  { type: "paragraph", label: "Paragraph" }, { type: "heading", label: "Heading" },
  { type: "quote", label: "Quote" }, { type: "unordered_list", label: "Bulleted list" },
  { type: "ordered_list", label: "Numbered list" }, { type: "link", label: "Link" }
];

export function ContentEditor({ value, onChange, disabled }: {
  value: ArticleContent; onChange: (_value: ArticleContent) => void; disabled: boolean;
}) {
  const id = useId();
  const [nextType, setNextType] = useState<ArticleBlock["type"]>("paragraph");
  const [announcement, setAnnouncement] = useState("");
  const changeBlock = (index: number, block: ArticleBlock) => onChange({
    version: 1, blocks: value.blocks.map((current, position) => position === index ? block : current)
  });
  const move = (index: number, direction: -1 | 1) => {
    const blocks = [...value.blocks];
    [blocks[index], blocks[index + direction]] = [blocks[index + direction], blocks[index]];
    onChange({ version: 1, blocks });
    setAnnouncement(`Block ${index + 1} moved to position ${index + direction + 1}.`);
  };
  return <fieldset disabled={disabled}><legend>Structured article body</legend>
    <p id={`${id}-help`}>Add paragraphs, headings, quotes, lists or HTTP(S) links. Text is always literal: HTML and embeds are not interpreted.</p>
    {value.blocks.map((block, index) => {
      const label = types.find((item) => item.type === block.type)!.label;
      const textId = `${id}-text-${index}`;
      return <fieldset key={index}><legend>Block {index + 1}: {label}</legend>
        {block.type === "unordered_list" || block.type === "ordered_list" ? <>
          {block.items.map((item, itemIndex) => <div key={itemIndex}>
            <label htmlFor={`${textId}-${itemIndex}`}>List item {itemIndex + 1} in block {index + 1}</label>
            <textarea id={`${textId}-${itemIndex}`} maxLength={100000} value={item} aria-describedby={`${id}-help`}
              onChange={(event) => changeBlock(index, { ...block, items: block.items.map((current, position) => position === itemIndex ? event.target.value : current) })} />
            <button type="button" onClick={() => changeBlock(index, { ...block, items: block.items.filter((_, position) => position !== itemIndex) })}>Remove item {itemIndex + 1} from block {index + 1}</button>
          </div>)}
          <button type="button" disabled={disabled || block.items.length >= 100}
            onClick={() => changeBlock(index, { ...block, items: [...block.items, ""] })}>Add list item to block {index + 1}</button>
        </> : <>
          <label htmlFor={textId}>{index === 0 && block.type === "paragraph" ? "Body" : `${label} text ${index + 1}`}</label>
          <textarea id={textId} maxLength={100000} value={block.text} aria-describedby={`${id}-help`}
            onChange={(event) => changeBlock(index, { ...block, text: event.target.value })} />
          {block.type === "link" && <>
            <label htmlFor={`${id}-url-${index}`}>Link URL {index + 1}</label>
            <input id={`${id}-url-${index}`} type="text" inputMode="url" maxLength={2048} value={block.url}
              onChange={(event) => changeBlock(index, { ...block, url: event.target.value })} />
          </>}
        </>}
        <div className="workflow-actions">
          <button type="button" disabled={disabled || index === 0} onClick={() => move(index, -1)}>Move block {index + 1} up</button>
          <button type="button" disabled={disabled || index === value.blocks.length - 1} onClick={() => move(index, 1)}>Move block {index + 1} down</button>
          <button type="button" onClick={() => {
            onChange({ version: 1, blocks: value.blocks.filter((_, position) => position !== index) });
            setAnnouncement(`Block ${index + 1} removed.`);
          }}>Remove block {index + 1}</button>
        </div>
      </fieldset>;
    })}
    <label htmlFor={`${id}-type`}>New block type</label>
    <select id={`${id}-type`} value={nextType} onChange={(event) => setNextType(event.target.value as ArticleBlock["type"])}>
      {types.map(({ type, label }) => <option key={type} value={type}>{label}</option>)}
    </select>
    <button type="button" disabled={disabled || value.blocks.length >= MAX_CONTENT_BLOCKS} onClick={() => {
      const block: ArticleBlock = nextType === "unordered_list" || nextType === "ordered_list"
        ? { type: nextType, items: [""] } : nextType === "link" ? { type: "link", text: "", url: "" } : { type: nextType, text: "" };
      onChange({ version: 1, blocks: [...value.blocks, block] });
      setAnnouncement(`Block ${value.blocks.length + 1} added.`);
    }}>Add content block</button>
    <p aria-live="polite">{announcement}</p>
  </fieldset>;
}
