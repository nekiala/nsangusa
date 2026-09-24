create table newsletter_preference_links (
  id uuid primary key,
  subscription_id uuid not null references newsletter_subscriptions(id),
  verified_at timestamptz not null,
  created_at timestamptz not null,
  expires_at timestamptz not null,
  status varchar(32) not null default 'pending',
  claimed_at timestamptz,
  completed_at timestamptz,
  failure_code varchar(100),
  check (expires_at > created_at),
  check (status in ('pending', 'sending', 'accepted', 'expired', 'reconciliation_required'))
);
create index idx_newsletter_preference_links_subscription
  on newsletter_preference_links(subscription_id, created_at desc);
create index idx_newsletter_preference_links_queue
  on newsletter_preference_links(created_at) where status = 'pending';

alter table newsletter_deliveries
  add column delivery_confirmed_at timestamptz,
  add column reconciled_at timestamptz,
  add column reconciled_by uuid,
  add column reconciliation_outcome varchar(32),
  add column reconciliation_evidence varchar(200);

create table newsletter_delivery_attempts (
  id uuid primary key,
  delivery_id uuid not null references newsletter_deliveries(id),
  attempt_number integer not null,
  status varchar(32) not null,
  started_at timestamptz not null,
  completed_at timestamptz,
  failure_code varchar(200),
  provider_message_id varchar(300),
  unique(delivery_id, attempt_number)
);
create index idx_newsletter_attempt_history on newsletter_delivery_attempts(delivery_id, started_at desc);
create index idx_newsletter_consent_history on consent_records(subscription_id, occurred_at desc);
create index idx_newsletter_subscription_queue on newsletter_subscriptions(status, frequency, consent_at desc);
create index idx_newsletter_delivery_queue on newsletter_deliveries(status, created_at desc);

-- SMTP has no provider retry guarantee. Such attempts are still durable, but never replayable.
create or replace function require_newsletter_provider_idempotency()
returns trigger language plpgsql as $$
begin
  if new.attempt_token is null or new.attempt_started_at is null then
    raise exception 'Newsletter delivery requires a durable leased attempt';
  end if;
  return new;
end;
$$;

create function prevent_unsafe_newsletter_retry() returns trigger language plpgsql as $$
begin
  if new.attempt_token is distinct from old.attempt_token and
      (old.status in ('delivered', 'bounced', 'reconciled', 'reconciliation_required')
       or old.provider_idempotency_applied is not true) then
    raise exception 'Newsletter delivery requires reconciliation, not a resend';
  end if;
  return new;
end;
$$;
create trigger trg_prevent_unsafe_newsletter_retry before update on newsletter_deliveries
  for each row execute function prevent_unsafe_newsletter_retry();

-- Existing "delivered" rows establish provider acceptance, not inbox delivery.
create function record_newsletter_attempt() returns trigger language plpgsql as $$
begin
  if TG_OP = 'UPDATE' and new.status = 'reconciled' then return new; end if;
  if new.attempt_token is not null and new.attempt_started_at is not null then
    insert into newsletter_delivery_attempts(id, delivery_id, attempt_number, status, started_at,
      completed_at, failure_code, provider_message_id)
    values(new.attempt_token, new.id, new.attempt_count,
      case when new.status = 'delivered' and new.delivery_confirmed_at is not null then 'delivery_confirmed'
           when new.status = 'delivered' then 'provider_accepted' else new.status end,
      new.attempt_started_at, case when new.status <> 'pending' then now() end,
      new.failure_code, new.provider_message_id)
    on conflict (delivery_id, attempt_number) do update set
      status = excluded.status, completed_at = excluded.completed_at,
      failure_code = excluded.failure_code, provider_message_id = excluded.provider_message_id;
  end if;
  return new;
end;
$$;
create trigger trg_record_newsletter_attempt after insert or update on newsletter_deliveries
  for each row execute function record_newsletter_attempt();

-- Only recorded timestamps are backfilled; missing historical attempts are not fabricated.
insert into newsletter_delivery_attempts(id, delivery_id, attempt_number, status, started_at,
  completed_at, failure_code, provider_message_id)
select attempt_token, id, attempt_count,
  case when status = 'delivered' then 'provider_accepted' else status end,
  attempt_started_at, delivered_at, failure_code, provider_message_id
from newsletter_deliveries where attempt_token is not null and attempt_started_at is not null;

create function record_newsletter_consent() returns trigger language plpgsql as $$
declare consent_action varchar(32);
begin
  if TG_OP = 'INSERT' then
    consent_action := 'requested';
  elsif old.status is distinct from new.status then
    consent_action := new.status;
  elsif old.frequency is distinct from new.frequency then
    consent_action := 'preferences_changed';
  else
    return new;
  end if;
  insert into consent_records(id, subscription_id, action, source, occurred_at)
  values(gen_random_uuid(), new.id, consent_action,
    case when consent_action = 'requested' then new.consent_source else 'newsletter-lifecycle' end, now());
  return new;
end;
$$;
create trigger trg_record_newsletter_consent after insert or update on newsletter_subscriptions
  for each row execute function record_newsletter_consent();

insert into consent_records(id, subscription_id, action, source, occurred_at)
select gen_random_uuid(), s.id, 'requested', s.consent_source, s.consent_at
from newsletter_subscriptions s
where not exists(select 1 from consent_records c where c.subscription_id = s.id and c.action = 'requested');
insert into consent_records(id, subscription_id, action, source, occurred_at)
select gen_random_uuid(), s.id, 'confirmed', 'recorded-verification', s.verified_at
from newsletter_subscriptions s where s.verified_at is not null
and not exists(select 1 from consent_records c where c.subscription_id = s.id and c.action = 'confirmed');
