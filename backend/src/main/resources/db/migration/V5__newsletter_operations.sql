alter table newsletter_campaigns
  add column status varchar(32) not null default 'pending',
  add column completed_at timestamptz;

create table newsletter_suppressions (
  id uuid primary key,
  email_hash varchar(64) not null unique,
  reason varchar(100) not null,
  created_at timestamptz not null
);

create index idx_newsletter_delivery_provider_message
  on newsletter_deliveries(provider_message_id)
  where provider_message_id is not null;
