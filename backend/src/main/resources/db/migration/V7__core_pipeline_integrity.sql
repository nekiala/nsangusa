alter table source_posts
  add column edit_chain_id varchar(100),
  add column conversation_id varchar(100),
  add column last_seen_at timestamptz,
  add column last_checked_at timestamptz;

create or replace function populate_source_pipeline_defaults()
returns trigger
language plpgsql
as $$
begin
  new.edit_chain_id = coalesce(new.edit_chain_id, new.post_id);
  new.last_seen_at = coalesce(new.last_seen_at, new.ingested_at, now());
  return new;
end;
$$;

create trigger trg_populate_source_pipeline_defaults
before insert on source_posts
for each row execute function populate_source_pipeline_defaults();

update source_posts
   set edit_chain_id = post_id,
       last_seen_at = ingested_at;

alter table source_posts
  alter column edit_chain_id set not null,
  alter column last_seen_at set not null;

create unique index uq_source_posts_edit_chain
  on source_posts(edit_chain_id);

create index idx_source_posts_reconciliation
  on source_posts(monitored_account_id, status, last_checked_at, published_at);

alter table story_candidates
  add column conversation_id varchar(100),
  add column cluster_terms text not null default '',
  add column last_source_at timestamptz,
  add column analysis_requested_at timestamptz,
  add column source_count integer not null default 1,
  add column correlation_id uuid,
  add column last_source_event_id uuid;

create or replace function populate_story_pipeline_defaults()
returns trigger
language plpgsql
as $$
begin
  new.last_source_at = coalesce(new.last_source_at, new.created_at, now());
  new.correlation_id = coalesce(new.correlation_id, new.id);
  new.cluster_terms = coalesce(new.cluster_terms, '');
  new.source_count = coalesce(new.source_count, 1);
  return new;
end;
$$;

create trigger trg_populate_story_pipeline_defaults
before insert on story_candidates
for each row execute function populate_story_pipeline_defaults();

update story_candidates
   set last_source_at = created_at,
       correlation_id = id;

alter table story_candidates
  alter column last_source_at set not null,
  alter column correlation_id set not null;

create index idx_story_candidates_collecting
  on story_candidates(topic, status, last_source_at desc);

create table story_candidate_sources (
  id uuid primary key,
  story_candidate_id uuid not null references story_candidates(id) on delete cascade,
  source_post_id uuid not null references source_posts(id),
  account varchar(100) not null,
  post_id varchar(100) not null,
  url varchar(1000) not null,
  published_at timestamptz not null,
  normalized_text text not null,
  added_at timestamptz not null,
  unique (story_candidate_id, source_post_id),
  unique (source_post_id)
);

insert into story_candidate_sources (
  id,
  story_candidate_id,
  source_post_id,
  account,
  post_id,
  url,
  published_at,
  normalized_text,
  added_at
)
select
  gen_random_uuid(),
  sc.id,
  sp.id,
  sp.handle,
  sp.post_id,
  sp.canonical_url,
  sp.published_at,
  sp.permitted_text,
  sc.created_at
from story_candidates sc
join source_posts sp on sp.id = sc.primary_source_post_id;

create index idx_story_candidate_sources_story
  on story_candidate_sources(story_candidate_id, published_at);

update story_candidate_sources scs
   set normalized_text = '[source restricted]'
  from source_posts sp
 where sp.id = scs.source_post_id
   and (sp.status = 'deleted' or sp.status like 'excluded_%');

create or replace function populate_primary_story_source()
returns trigger
language plpgsql
as $$
begin
  insert into story_candidate_sources (
    id,
    story_candidate_id,
    source_post_id,
    account,
    post_id,
    url,
    published_at,
    normalized_text,
    added_at
  )
  select
    gen_random_uuid(),
    new.id,
    sp.id,
    sp.handle,
    sp.post_id,
    sp.canonical_url,
    sp.published_at,
    sp.permitted_text,
    coalesce(new.created_at, now())
  from source_posts sp
  where sp.id = new.primary_source_post_id
  on conflict (source_post_id) do nothing;
  return new;
end;
$$;

create trigger trg_populate_primary_story_source
after insert on story_candidates
for each row execute function populate_primary_story_source();

alter table scheduled_publications
  add column scheduled_by uuid;

update scheduled_publications
   set scheduled_by = '00000000-0000-0000-0000-000000000001';

alter table scheduled_publications
  alter column scheduled_by set default '00000000-0000-0000-0000-000000000001',
  alter column scheduled_by set not null;

alter table articles
  add column pending_image_generation_id uuid references image_generations(id),
  add column approved_image_generation_id uuid references image_generations(id),
  add column image_approval_required boolean not null default false;

alter table image_generations
  add column approval_event_expected boolean not null default false;

update articles
   set approved_image_generation_id = approved.id,
       hero_object_key = approved.object_key,
       image_alt_text = approved.alt_text,
       generated_image = true,
       state =
         case when articles.state = 'DRAFTING' then 'AWAITING_REVIEW'
              else articles.state end
  from (
    select distinct on (article_id)
           id,
           article_id,
           object_key,
           alt_text
      from image_generations
     where safety_status = 'approved'
     order by article_id, approved_at desc nulls last, created_at desc
  ) approved
 where approved.article_id = articles.id;

update articles
   set pending_image_generation_id = pending.id
  from (
    select distinct on (article_id)
           id,
           article_id
      from image_generations
     where safety_status <> 'approved'
     order by article_id, created_at desc
  ) pending
 where pending.article_id = articles.id;

update articles
   set image_approval_required =
       approved_image_generation_id is null
       and (
         pending_image_generation_id is not null
         or (state = 'DRAFTING' and generated_image = false)
       );

create or replace function populate_article_image_defaults()
returns trigger
language plpgsql
as $$
begin
  if new.state = 'DRAFTING' and new.generated_image = false then
    new.image_approval_required = true;
  end if;
  return new;
end;
$$;

create trigger trg_populate_article_image_defaults
before insert on articles
for each row execute function populate_article_image_defaults();

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

create trigger trg_select_approved_image_generation
after update of safety_status on image_generations
for each row execute function select_approved_image_generation();

create or replace function prevent_unapproved_image_publication()
returns trigger
language plpgsql
as $$
begin
  if new.state in ('APPROVED', 'SCHEDULED', 'PUBLISHED')
     and new.image_approval_required then
    raise exception 'Article % has an unapproved generated image', new.id
      using errcode = '23514';
  end if;
  return new;
end;
$$;

create trigger trg_prevent_unapproved_image_publication
before insert or update of state, image_approval_required on articles
for each row execute function prevent_unapproved_image_publication();

alter table newsletter_deliveries
  add column provider_idempotency_key varchar(100),
  add column provider_idempotency_applied boolean not null default false,
  add column attempt_token uuid,
  add column attempt_started_at timestamptz;

create or replace function populate_newsletter_idempotency_key()
returns trigger
language plpgsql
as $$
begin
  new.provider_idempotency_key =
    coalesce(
      new.provider_idempotency_key,
      'newsletter-' || md5(new.subscription_id::text || ':' || new.campaign_key)
    );
  return new;
end;
$$;

create trigger trg_populate_newsletter_idempotency_key
before insert on newsletter_deliveries
for each row execute function populate_newsletter_idempotency_key();

create or replace function require_newsletter_provider_idempotency()
returns trigger
language plpgsql
as $$
begin
  if new.provider_idempotency_applied is not true
     or new.attempt_token is null
     or new.attempt_started_at is null then
    raise exception 'Newsletter delivery requires a provider-idempotent leased attempt';
  end if;
  return new;
end;
$$;

create trigger trg_require_newsletter_provider_idempotency
before insert on newsletter_deliveries
for each row execute function require_newsletter_provider_idempotency();

update newsletter_deliveries
   set provider_idempotency_key =
       'newsletter-' || md5(subscription_id::text || ':' || campaign_key);

alter table newsletter_deliveries
  alter column provider_idempotency_key set not null;

create unique index uq_newsletter_provider_idempotency
  on newsletter_deliveries(provider_idempotency_key);

insert into newsletter_campaigns (
  id,
  campaign_key,
  article_id,
  campaign_type,
  created_at,
  status
)
values
  (
    gen_random_uuid(),
    'daily:' || to_char(current_timestamp at time zone 'UTC', 'YYYY-MM-DD'),
    null,
    'daily',
    now(),
    'reconciliation_required'
  ),
  (
    gen_random_uuid(),
    'weekly:' || to_char(current_timestamp at time zone 'UTC', 'YYYY-MM-DD'),
    null,
    'weekly',
    now(),
    'reconciliation_required'
  )
on conflict (campaign_key) do nothing;

create or replace function reconcile_restricted_source()
returns trigger
language plpgsql
as $$
begin
  if new.status = 'deleted' or new.status like 'excluded_%' then
    update story_candidate_sources
       set normalized_text = '[source restricted]'
     where source_post_id = new.id;

    insert into event_replay_suppressions(aggregate_id, reason, suppressed_at)
    values (
      new.id,
      coalesce(new.compliance_reason, 'source excluded'),
      now()
    )
    on conflict (aggregate_id) do update
      set reason = excluded.reason, suppressed_at = excluded.suppressed_at;

    insert into event_replay_suppressions(aggregate_id, reason, suppressed_at)
    select scs.story_candidate_id, coalesce(new.compliance_reason, 'source excluded'), now()
      from story_candidate_sources scs
     where scs.source_post_id = new.id
    on conflict (aggregate_id) do update
      set reason = excluded.reason, suppressed_at = excluded.suppressed_at;

    insert into event_replay_suppressions(aggregate_id, reason, suppressed_at)
    select ars.article_id, coalesce(new.compliance_reason, 'source excluded'), now()
      from article_sources ars
     where ars.source_post_id = new.id
    on conflict (aggregate_id) do update
      set reason = excluded.reason, suppressed_at = excluded.suppressed_at;

    update story_candidates sc
       set status = 'excluded_compliance'
     where exists (
       select 1
         from story_candidate_sources scs
        where scs.story_candidate_id = sc.id
          and scs.source_post_id = new.id
     )
       and sc.status <> 'excluded_compliance';

    update articles a
       set state = 'UNPUBLISHED',
           unpublished_at = now(),
           comments_enabled = false,
           updated_at = now()
     where a.state in ('PUBLISHED', 'SCHEDULED', 'APPROVED')
       and exists (
         select 1 from article_sources ars
          where ars.article_id = a.id
            and ars.source_post_id = new.id
       );

    delete from article_search_documents d
     where exists (
       select 1 from article_sources ars
        where ars.article_id = d.article_id
          and ars.source_post_id = new.id
     );

    update scheduled_publications s
       set status = 'cancelled'
     where status = 'scheduled'
       and exists (
         select 1 from article_sources ars
          where ars.article_id = s.article_id
            and ars.source_post_id = new.id
       );

    update outbox_events
       set envelope_json = '{"redacted":true,"reason":"source_restricted"}',
           published_at = coalesce(published_at, now())
     where aggregate_id = new.id
        or aggregate_id in (
          select story_candidate_id
            from story_candidate_sources
           where source_post_id = new.id
        )
        or aggregate_id in (
          select article_id from article_sources where source_post_id = new.id
        );

    update failed_events
       set payload = '{"redacted":true,"reason":"source_restricted"}',
           status = 'suppressed',
           last_updated_at = now()
     where aggregate_id = new.id
        or aggregate_id in (
          select story_candidate_id
            from story_candidate_sources
           where source_post_id = new.id
        )
        or aggregate_id in (
          select article_id from article_sources where source_post_id = new.id
        );
  end if;
  return new;
end;
$$;

create or replace function prevent_restricted_story_candidate_source()
returns trigger
language plpgsql
as $$
begin
  if exists (
    select 1 from source_posts
     where id = new.source_post_id
       and status <> 'active'
  ) then
    raise exception 'Restricted source % cannot join a story candidate', new.source_post_id
      using errcode = '23514';
  end if;
  return new;
end;
$$;

create trigger trg_prevent_restricted_story_candidate_source
before insert or update of source_post_id on story_candidate_sources
for each row execute function prevent_restricted_story_candidate_source();
