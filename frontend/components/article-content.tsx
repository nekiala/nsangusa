import { resolvedContent, type ArticleContent } from "@/lib/article-content";

export function ArticleContentView({ content, body = "" }: { content?: ArticleContent | null; body?: string }) {
  return resolvedContent(content, body).blocks.map((block, index) => {
    switch (block.type) {
      case "heading": return <h2 className="plain-text" key={index}>{block.text}</h2>;
      case "quote": return <blockquote className="plain-text" key={index}>{block.text}</blockquote>;
      case "unordered_list": return <ul key={index}>{block.items.map((item, itemIndex) => <li className="plain-text" key={itemIndex}>{item}</li>)}</ul>;
      case "ordered_list": return <ol key={index}>{block.items.map((item, itemIndex) => <li className="plain-text" key={itemIndex}>{item}</li>)}</ol>;
      case "link": return <p className="plain-text" key={index}><a href={block.url} rel="noopener noreferrer">{block.text}</a></p>;
      case "paragraph": return <p className="plain-text" key={index}>{block.text}</p>;
    }
  });
}
