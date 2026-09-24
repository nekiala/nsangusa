alter table scheduled_publications
  add column version bigint not null default 0,
  add column article_version bigint,
  add column updated_by uuid,
  add column created_at timestamptz not null default now(),
  add column updated_at timestamptz not null default now(),
  add column completed_at timestamptz,
  add column last_attempt_at timestamptz,
  add column attempt_count integer not null default 0,
  add column last_error varchar(1000);

update scheduled_publications
   set updated_by = scheduled_by,
       completed_at = case when status in ('published', 'cancelled') then now() else null end;

-- Historical schedules never captured the approved version. Do not bless the current article
-- version implicitly: editors must cancel these schedules and explicitly schedule again.
update scheduled_publications
   set status = 'failed',
       last_error = 'Legacy schedule has no approved article version (previous status: '
                    || status || '); cancel it and schedule the reviewed article again'
 where status not in ('published', 'cancelled');

with ranked as (
  select id, row_number() over (partition by article_id order by scheduled_for desc, id) as position
    from scheduled_publications
   where status in ('scheduled', 'failed')
)
update scheduled_publications s
   set status = 'cancelled',
       completed_at = now(),
       last_error = 'Duplicate legacy schedule cancelled; review the retained schedule'
  from ranked r
 where s.id = r.id and r.position > 1;

alter table scheduled_publications
  alter column updated_by set not null,
  add constraint ck_publication_schedule_status
    check (status in ('scheduled', 'published', 'cancelled', 'failed')),
  add constraint ck_publication_schedule_version
    check (version >= 0 and (article_version is null or article_version >= 0)),
  add constraint ck_publication_schedule_approved_version
    check (status <> 'scheduled' or article_version is not null),
  add constraint ck_publication_schedule_attempt_count check (attempt_count >= 0);

create unique index uq_publication_schedule_active_article
  on scheduled_publications(article_id)
  where status in ('scheduled', 'failed');

create index idx_publication_schedule_due
  on scheduled_publications(scheduled_for, id) where status = 'scheduled';

create index idx_publication_schedule_inventory
  on scheduled_publications(scheduled_for desc, id);

create index idx_publication_schedule_article_inventory
  on scheduled_publications(article_id, scheduled_for desc, id);

create index idx_publication_schedule_status_inventory
  on scheduled_publications(status, scheduled_for desc, id);

-- Compliance reconciliation also cancels schedules directly in SQL. Keep those cancellations
-- visible and invalidate editor versions just as application-originated mutations do.
create function track_external_publication_schedule_change()
returns trigger
language plpgsql
as $$
begin
  if new.version = old.version then
    new.version = old.version + 1;
    new.updated_at = now();
    if new.status = 'cancelled' and old.status <> 'cancelled' then
      new.completed_at = now();
      new.updated_by = '00000000-0000-0000-0000-000000000001';
      new.last_error = coalesce(new.last_error, 'Schedule cancelled by source eligibility reconciliation');
      insert into audit_records(id, actor_id, action, target_type, target_id, occurred_at, metadata)
      values (
        gen_random_uuid(), new.updated_by, 'PUBLICATION_SCHEDULE_CANCELLED',
        'publication_schedule', new.id, now(),
        jsonb_build_object('articleId', new.article_id::text, 'version', new.version::text,
                           'scheduledBy', new.scheduled_by::text, 'reason', new.last_error)
      );
    end if;
  end if;
  return new;
end;
$$;

create trigger trg_track_external_publication_schedule_change
before update on scheduled_publications
for each row execute function track_external_publication_schedule_change();
