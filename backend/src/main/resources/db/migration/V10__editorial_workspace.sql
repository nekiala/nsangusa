alter table articles
  add column story_candidate_id uuid references story_candidates(id);

create index idx_articles_story_candidate on articles(story_candidate_id);

alter table article_revisions add column ai_generation_result jsonb;

create index idx_article_revision_ai_sources
  on article_revisions using gin ((ai_generation_result -> 'sources'))
  where ai_generation_result is not null;

create function redact_revision_ai_evidence()
returns trigger
language plpgsql
as $$
begin
  update article_revisions
     set ai_generation_result = null
   where ai_generation_result is not null
     and ai_generation_result -> 'sources'
         @> jsonb_build_array(jsonb_build_object('sourcePostId', new.id::text));
  return new;
end;
$$;

create trigger trg_redact_revision_ai_evidence
after update of status, permitted_text, post_id, handle, canonical_url, published_at on source_posts
for each row
when (old.status is distinct from new.status
   or old.permitted_text is distinct from new.permitted_text
   or old.post_id is distinct from new.post_id
   or old.handle is distinct from new.handle
   or old.canonical_url is distinct from new.canonical_url
   or old.published_at is distinct from new.published_at)
execute function redact_revision_ai_evidence();

alter table story_candidates
  add column draft_article_id uuid,
  add column regenerated_from_id uuid references story_candidates(id);

alter table story_candidate_sources
  drop constraint story_candidate_sources_source_post_id_key;

create index idx_story_candidate_sources_source on story_candidate_sources(source_post_id);

create or replace function populate_primary_story_source()
returns trigger
language plpgsql
as $$
begin
  insert into story_candidate_sources (
    id, story_candidate_id, source_post_id, account, post_id, url, published_at,
    normalized_text, added_at
  )
  select gen_random_uuid(), new.id, sp.id, sp.handle, sp.post_id, sp.canonical_url,
         sp.published_at, sp.permitted_text, coalesce(new.created_at, now())
    from source_posts sp
   where sp.id = new.primary_source_post_id
  on conflict (story_candidate_id, source_post_id) do nothing;
  return new;
end;
$$;

create or replace function select_approved_image_generation()
returns trigger
language plpgsql
as $$
begin
  if new.safety_status = 'approved'
     and old.safety_status is distinct from new.safety_status then
    update articles
       set approved_image_generation_id = new.id,
           pending_image_generation_id =
             case when pending_image_generation_id = new.id then null
                  else pending_image_generation_id end,
           hero_object_key = new.object_key,
           image_alt_text = new.alt_text,
           generated_image = true,
           image_approval_required = false,
           state =
             case
               when new.approval_event_expected = false and state = 'DRAFTING'
                 then 'AWAITING_REVIEW'
               else state
             end,
           version = version + 1,
           updated_at = now()
     where id = new.article_id
       and state not in ('ARCHIVED', 'REJECTED');
    if not found then
      raise exception 'Rejected or archived article % cannot select an image', new.article_id
        using errcode = '23514';
    end if;
  end if;
  return new;
end;
$$;
