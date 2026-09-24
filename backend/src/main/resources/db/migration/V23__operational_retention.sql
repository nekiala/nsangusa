-- No policies are seeded: an operator must supply the independently approved policy reference.
create table operational_retention_policies (
  id uuid primary key,
  data_class varchar(80) not null check (
    data_class in ('published_outbox_payload', 'suppressed_failure_payload')),
  retain_seconds bigint not null check (retain_seconds between 1 and 315360000),
  approval_reference varchar(500) not null check (length(trim(approval_reference)) > 0),
  approved_by varchar(200) not null check (length(trim(approved_by)) > 0),
  approved_at timestamptz not null,
  expires_at timestamptz not null,
  enabled boolean not null default false,
  check (expires_at > approved_at)
);

-- Null class/record means a global hold. A record may be an event ID or its aggregate ID.
create table operational_legal_holds (
  id uuid primary key,
  data_class varchar(80) check (
    data_class in ('published_outbox_payload', 'suppressed_failure_payload')),
  record_id uuid,
  reference varchar(500) not null check (length(trim(reference)) > 0),
  created_at timestamptz not null default now(),
  released_at timestamptz
);
create index idx_operational_active_holds on operational_legal_holds(data_class, record_id)
  where released_at is null;

create function serialize_operational_retention() returns trigger language plpgsql as $$
begin
  perform pg_advisory_xact_lock(684613092);
  return null;
end;
$$;
create trigger trg_serialize_operational_holds
before insert or update or delete on operational_legal_holds
for each statement execute function serialize_operational_retention();
create trigger trg_serialize_operational_policies
before insert or update or delete on operational_retention_policies
for each statement execute function serialize_operational_retention();

create function apply_operational_retention(policy_id uuid, batch_size integer, dry_run boolean)
returns jsonb language plpgsql as $$
declare
  policy operational_retention_policies%rowtype;
  cutoff timestamptz;
  ids uuid[];
  changed integer := 0;
  evidence jsonb;
begin
  if batch_size is null or batch_size < 1 or batch_size > 1000 or dry_run is null then
    raise exception 'Explicit dry_run and batch_size between 1 and 1000 required';
  end if;
  perform pg_advisory_xact_lock(684613092);
  select * into strict policy from operational_retention_policies where id = policy_id;
  if not policy.enabled or policy.approved_at > now() or policy.expires_at <= now() then
    raise exception 'Enabled, currently approved retention policy required';
  end if;
  cutoff := now() - make_interval(secs => policy.retain_seconds::double precision);
  if policy.data_class = 'published_outbox_payload' then
    select array_agg(id) into ids from (
      select e.id from outbox_events e
      where e.published_at < cutoff and e.envelope_json <> '{"redacted":true,"reason":"retention"}'
        and not exists (
          select 1 from operational_legal_holds h where h.released_at is null
          and (h.data_class is null or h.data_class = policy.data_class)
          and (h.record_id is null or h.record_id in (e.id, e.aggregate_id)))
      order by e.published_at, e.id limit batch_size for update of e
    ) candidates;
    if not dry_run then
      update outbox_events set envelope_json = '{"redacted":true,"reason":"retention"}'
        where id = any(ids);
      get diagnostics changed = row_count;
    end if;
  else
    select array_agg(id) into ids from (
      select e.id from failed_events e
      where e.status = 'suppressed' and e.last_updated_at < cutoff
        and e.payload <> '{"redacted":true,"reason":"retention"}'
        and not exists (
          select 1 from operational_legal_holds h where h.released_at is null
          and (h.data_class is null or h.data_class = policy.data_class)
          and (h.record_id is null or h.record_id in (e.id, e.event_id, e.aggregate_id)))
      order by e.last_updated_at, e.id limit batch_size for update of e
    ) candidates;
    if not dry_run then
      update failed_events set payload = '{"redacted":true,"reason":"retention"}',
        exception_message = null where id = any(ids);
      get diagnostics changed = row_count;
    end if;
  end if;
  evidence := jsonb_build_object(
    'policyId', policy.id, 'approvalReference', policy.approval_reference,
    'dataClass', policy.data_class, 'cutoff', cutoff, 'dryRun', dry_run,
    'selected', coalesce(cardinality(ids), 0), 'changed', changed, 'batchLimit', batch_size,
    'activeHolds', (select count(*) from operational_legal_holds where released_at is null));
  insert into audit_records(id, action, target_type, target_id, occurred_at, metadata)
    values (gen_random_uuid(), 'OPERATIONAL_RETENTION', 'retention_policy', policy.id, now(), evidence);
  return evidence;
end;
$$;
revoke all on function apply_operational_retention(uuid, integer, boolean) from public;

create function prevent_suppressed_article_publication() returns trigger language plpgsql as $$
begin
  if new.state in ('APPROVED', 'SCHEDULED', 'PUBLISHED') and exists (
    select 1 from event_replay_suppressions where aggregate_id = new.id
  ) then
    raise exception 'Suppressed article % requires compliance reconciliation before publication', new.id
      using errcode = '23514';
  end if;
  return new;
end;
$$;
create trigger trg_prevent_suppressed_article_publication
before insert or update of state on articles
for each row execute function prevent_suppressed_article_publication();
