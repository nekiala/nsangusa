alter table articles add column content jsonb;

-- Legacy bodies are interpreted as paragraphs on read and persisted on the next editorial edit.
-- Existing revision snapshots remain unchanged; source redaction already clears the entire snapshot.
alter table articles add constraint chk_article_content_version
  check (content is null or (
    jsonb_typeof(content) = 'object'
    and content ?& array['version', 'blocks']
    and content -> 'version' = '1'::jsonb
    and jsonb_typeof(content -> 'blocks') = 'array'
  ));
