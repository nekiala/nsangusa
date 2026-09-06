alter table monitored_x_accounts
  add column if not exists updated_at timestamptz not null default now(),
  add column if not exists removed_at timestamptz,
  add column if not exists last_sync_attempt_at timestamptz,
  add column if not exists rate_limit_limit integer,
  add column if not exists rate_limit_remaining integer,
  add column if not exists consecutive_errors integer not null default 0;

alter table blocked_source_accounts
  add column if not exists actor_id uuid;

alter table source_posts
  add column if not exists compliance_action varchar(32),
  add column if not exists reconciliation_state varchar(32),
  add column if not exists compliance_requested_at timestamptz,
  add column if not exists compliance_reconciled_at timestamptz,
  add column if not exists compliance_reason varchar(500),
  add column if not exists compliance_error text,
  add column if not exists excluded_at timestamptz,
  add column if not exists content_deleted_at timestamptz;

alter table source_posts
  add constraint chk_source_reconciliation_state
  check (reconciliation_state is null or reconciliation_state in ('pending', 'reconciled', 'failed'));

create table source_tombstones (
  id uuid primary key,
  source_post_id uuid not null unique references source_posts(id),
  post_id varchar(100) not null unique,
  account_id varchar(100) not null,
  reason varchar(500) not null,
  deleted_at timestamptz not null,
  actor_id uuid
);
create index idx_source_tombstones_account on source_tombstones(account_id, deleted_at desc);

create table source_compliance_actions (
  id uuid primary key,
  action varchar(100) not null,
  target_type varchar(100),
  target_key varchar(200),
  source_post_id uuid references source_posts(id),
  actor_id uuid,
  reason varchar(500) not null,
  occurred_at timestamptz not null,
  metadata text not null
);
create index idx_source_compliance_actions_source
  on source_compliance_actions(source_post_id, occurred_at desc);

create table event_replay_suppressions (
  aggregate_id uuid primary key,
  reason varchar(500) not null,
  actor_id uuid,
  suppressed_at timestamptz not null
);

create table failed_events (
  id uuid primary key,
  event_id uuid,
  event_type varchar(100),
  aggregate_id uuid,
  dlt_topic varchar(249) not null,
  dlt_partition integer not null,
  dlt_offset bigint not null,
  original_topic varchar(249) not null,
  original_partition integer not null,
  original_offset bigint not null,
  consumer_group varchar(255),
  payload text not null,
  exception_class varchar(500),
  exception_message text,
  delivery_attempt integer not null default 1,
  poison_message boolean not null default false,
  status varchar(32) not null,
  failed_at timestamptz not null,
  last_updated_at timestamptz not null,
  unique (dlt_topic, dlt_partition, dlt_offset)
);
create index idx_failed_events_status_time on failed_events(status, failed_at desc);
create index idx_failed_events_aggregate on failed_events(aggregate_id, failed_at desc);

create table event_replay_requests (
  id uuid primary key,
  actor_id uuid not null,
  reason varchar(500) not null,
  dry_run boolean not null,
  include_poison boolean not null,
  messages_per_second integer not null check (messages_per_second between 1 and 20),
  candidate_count integer not null,
  replayed_count integer not null default 0,
  blocked_count integer not null default 0,
  status varchar(32) not null,
  requested_at timestamptz not null,
  completed_at timestamptz,
  failed_from timestamptz,
  failed_to timestamptz,
  audit_metadata text not null
);
create index idx_event_replay_requests_status
  on event_replay_requests(status, requested_at);

create table event_replay_items (
  id uuid primary key,
  replay_request_id uuid not null references event_replay_requests(id) on delete cascade,
  failed_event_id uuid not null references failed_events(id),
  status varchar(32) not null,
  unique (replay_request_id, failed_event_id)
);
create index idx_event_replay_items_pending
  on event_replay_items(replay_request_id, status);

create table event_replay_records (
  id uuid primary key,
  replay_request_id uuid not null references event_replay_requests(id) on delete cascade,
  failed_event_id uuid not null references failed_events(id),
  event_id uuid,
  aggregate_id uuid,
  original_topic varchar(249) not null,
  outcome varchar(32) not null,
  detail varchar(2000),
  occurred_at timestamptz not null,
  unique (replay_request_id, failed_event_id)
);
create index idx_event_replay_records_event
  on event_replay_records(event_id, occurred_at desc);

create or replace function reconcile_restricted_source()
returns trigger
language plpgsql
as $$
begin
  if new.status = 'deleted' or new.status like 'excluded_%' then
    insert into event_replay_suppressions(aggregate_id, reason, suppressed_at)
    values (
      new.id,
      coalesce(new.compliance_reason, 'source excluded'),
      now()
    )
    on conflict (aggregate_id) do update
      set reason = excluded.reason, suppressed_at = excluded.suppressed_at;

    insert into event_replay_suppressions(aggregate_id, reason, suppressed_at)
    select sc.id, coalesce(new.compliance_reason, 'source excluded'), now()
      from story_candidates sc
     where sc.primary_source_post_id = new.id
    on conflict (aggregate_id) do update
      set reason = excluded.reason, suppressed_at = excluded.suppressed_at;

    insert into event_replay_suppressions(aggregate_id, reason, suppressed_at)
    select ars.article_id, coalesce(new.compliance_reason, 'source excluded'), now()
      from article_sources ars
     where ars.source_post_id = new.id
    on conflict (aggregate_id) do update
      set reason = excluded.reason, suppressed_at = excluded.suppressed_at;

    update story_candidates
       set status = 'excluded_compliance'
     where primary_source_post_id = new.id
       and status <> 'excluded_compliance';

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
          select id from story_candidates where primary_source_post_id = new.id
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
          select id from story_candidates where primary_source_post_id = new.id
        )
        or aggregate_id in (
          select article_id from article_sources where source_post_id = new.id
        );
  end if;
  return new;
end;
$$;

create trigger trg_reconcile_restricted_source
after insert or update of status on source_posts
for each row execute function reconcile_restricted_source();

create or replace function prevent_restricted_source_publication()
returns trigger
language plpgsql
as $$
begin
  if new.state in ('PUBLISHED', 'SCHEDULED') and exists (
    select 1
      from article_sources ars
      join source_posts sp on sp.id = ars.source_post_id
     where ars.article_id = new.id
       and sp.status <> 'active'
  ) then
    raise exception 'Article % has a restricted source and cannot be published', new.id
      using errcode = '23514';
  end if;
  return new;
end;
$$;

create trigger trg_prevent_restricted_source_publication
before insert or update of state on articles
for each row execute function prevent_restricted_source_publication();

create or replace function prevent_restricted_source_association()
returns trigger
language plpgsql
as $$
begin
  if exists (
    select 1 from source_posts
     where id = new.source_post_id
       and status <> 'active'
  ) then
    raise exception 'Restricted source % cannot be associated with an article', new.source_post_id
      using errcode = '23514';
  end if;
  return new;
end;
$$;

create trigger trg_prevent_restricted_source_association
before insert or update of source_post_id on article_sources
for each row execute function prevent_restricted_source_association();

create or replace function prevent_restricted_story_candidate()
returns trigger
language plpgsql
as $$
begin
  if exists (
    select 1 from source_posts
     where id = new.primary_source_post_id
       and status <> 'active'
  ) then
    raise exception 'Restricted source % cannot create a story candidate', new.primary_source_post_id
      using errcode = '23514';
  end if;
  return new;
end;
$$;

create trigger trg_prevent_restricted_story_candidate
before insert or update of primary_source_post_id on story_candidates
for each row execute function prevent_restricted_story_candidate();
