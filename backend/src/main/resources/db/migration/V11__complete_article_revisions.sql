alter table articles
  add column correction_note varchar(2000),
  add column approved_by uuid,
  add column approved_at timestamptz;

alter table article_revisions add column snapshot jsonb;
create index idx_article_revisions_page on article_revisions(article_id, revision_number desc);

create index idx_article_revision_snapshot_sources
  on article_revisions using gin ((snapshot -> 'sources')) where snapshot is not null;

create or replace function redact_revision_ai_evidence()
returns trigger language plpgsql as $$
begin
  update article_revisions
     set ai_generation_result = null
   where ai_generation_result is not null
     and ai_generation_result -> 'sources'
         @> jsonb_build_array(jsonb_build_object('sourcePostId', new.id::text));
  update article_revisions
     set snapshot = null
   where snapshot is not null
     and snapshot -> 'sources'
         @> jsonb_build_array(jsonb_build_object('sourcePostId', new.id::text));
  return new;
end;
$$;
