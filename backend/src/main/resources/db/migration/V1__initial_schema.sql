create table users (
  id uuid primary key,
  email varchar(320) not null unique,
  display_name varchar(100) not null,
  password_hash varchar(255) not null,
  email_verified boolean not null default false,
  enabled boolean not null default true,
  created_at timestamptz not null,
  deleted_at timestamptz,
  version bigint not null default 0
);

create table user_roles (
  user_id uuid not null references users(id) on delete cascade,
  role varchar(32) not null,
  primary key (user_id, role)
);

create table external_identities (
  id uuid primary key,
  user_id uuid not null references users(id) on delete cascade,
  issuer varchar(500) not null,
  subject varchar(500) not null,
  created_at timestamptz not null default now(),
  unique (issuer, subject)
);

create table verification_tokens (
  id uuid primary key,
  user_id uuid not null references users(id) on delete cascade,
  token_hash varchar(128) not null unique,
  purpose varchar(32) not null,
  expires_at timestamptz not null,
  used_at timestamptz
);

create table monitored_x_accounts (
  id uuid primary key,
  account_id varchar(100) not null unique,
  handle varchar(50) not null unique,
  display_name varchar(100) not null,
  topics varchar(1000) not null,
  relevance_threshold double precision not null check (relevance_threshold between 0 and 1),
  monitoring_enabled boolean not null default true,
  created_at timestamptz not null,
  last_successful_sync_at timestamptz,
  rate_limit_reset_at timestamptz,
  last_post_id varchar(100),
  last_error text,
  version bigint not null default 0
);

create table blocked_source_accounts (
  account_id varchar(100) primary key,
  reason varchar(500) not null,
  created_at timestamptz not null default now()
);

create table source_posts (
  id uuid primary key,
  monitored_account_id uuid not null references monitored_x_accounts(id),
  post_id varchar(100) not null unique,
  account_id varchar(100) not null,
  handle varchar(50) not null,
  canonical_url varchar(1000) not null,
  permitted_text text not null,
  published_at timestamptz not null,
  ingested_at timestamptz not null,
  status varchar(32) not null,
  version bigint not null default 0
);
create index idx_source_posts_account_published on source_posts(account_id, published_at desc);
create index idx_source_posts_status on source_posts(status);

create table source_relationships (
  id uuid primary key,
  source_post_id uuid not null references source_posts(id) on delete cascade,
  related_post_id varchar(100) not null,
  relationship_type varchar(32) not null,
  unique (source_post_id, related_post_id, relationship_type)
);

create table story_candidates (
  id uuid primary key,
  primary_source_post_id uuid not null references source_posts(id),
  topic varchar(100) not null,
  status varchar(32) not null,
  created_at timestamptz not null,
  version bigint not null default 0
);
create index idx_story_candidates_status_created on story_candidates(status, created_at);

create table ai_requests (
  id uuid primary key,
  story_candidate_id uuid not null references story_candidates(id),
  operation varchar(50) not null,
  provider varchar(100) not null,
  model varchar(100) not null,
  prompt_version varchar(100) not null,
  created_at timestamptz not null,
  completed_at timestamptz,
  input_tokens bigint not null default 0,
  output_tokens bigint not null default 0,
  status varchar(32),
  error_code varchar(100)
);
create index idx_ai_requests_story on ai_requests(story_candidate_id, created_at);

create table ai_results (
  id uuid primary key,
  request_id uuid not null references ai_requests(id),
  schema_version integer not null,
  result_json jsonb not null,
  confidence numeric(5,4),
  created_at timestamptz not null default now()
);

create table articles (
  id uuid primary key,
  slug varchar(250) not null unique,
  headline varchar(300) not null,
  summary text not null,
  body text not null,
  editorial_context text,
  seo_title varchar(300) not null,
  seo_description varchar(500) not null,
  topic varchar(100) not null,
  tags varchar(1000) not null,
  state varchar(32) not null,
  human_review_required boolean not null default true,
  comments_enabled boolean not null default true,
  generated_image boolean not null default false,
  hero_object_key varchar(1000),
  image_alt_text varchar(500),
  confidence numeric(5,4) not null,
  warnings text not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  published_at timestamptz,
  unpublished_at timestamptz,
  version bigint not null default 0
);
create index idx_articles_publication on articles(state, published_at desc);
create index idx_articles_topic on articles(topic, state, published_at desc);

create table article_sources (
  id uuid primary key,
  article_id uuid not null references articles(id) on delete cascade,
  source_post_id uuid not null references source_posts(id),
  account varchar(100) not null,
  post_id varchar(100) not null,
  url varchar(1000) not null,
  published_at timestamptz not null,
  unique (article_id, source_post_id)
);

create table article_revisions (
  id uuid primary key,
  article_id uuid not null references articles(id) on delete cascade,
  revision_number integer not null,
  headline varchar(300) not null,
  summary text not null,
  body text not null,
  reason varchar(50) not null,
  actor_id uuid,
  created_at timestamptz not null,
  unique (article_id, revision_number)
);

create table publication_records (
  id uuid primary key,
  article_id uuid not null references articles(id),
  action varchar(32) not null,
  actor_id uuid,
  occurred_at timestamptz not null,
  metadata jsonb not null default '{}'::jsonb
);

create table scheduled_publications (
  id uuid primary key,
  article_id uuid not null references articles(id),
  scheduled_for timestamptz not null,
  status varchar(32) not null,
  idempotency_key varchar(200) not null unique
);

create table media_assets (
  id uuid primary key,
  article_id uuid references articles(id),
  object_key varchar(1000) not null unique,
  media_type varchar(100) not null,
  width integer,
  height integer,
  sha256 varchar(64),
  created_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table image_generations (
  id uuid primary key,
  article_id uuid not null references articles(id),
  prompt text not null,
  alt_text varchar(500) not null,
  object_key varchar(1000) not null,
  provider varchar(100) not null,
  model varchar(100) not null,
  safety_status varchar(32) not null,
  created_at timestamptz not null
);

create table comments (
  id uuid primary key,
  article_id uuid not null references articles(id),
  author_id uuid not null references users(id),
  parent_id uuid references comments(id),
  body text not null,
  state varchar(32) not null,
  created_at timestamptz not null,
  deleted_at timestamptz,
  version bigint not null default 0
);
create index idx_comments_article_state on comments(article_id, state, created_at);

create table comment_reports (
  id uuid primary key,
  comment_id uuid not null references comments(id),
  reporter_id uuid not null references users(id),
  reason varchar(100) not null,
  details text,
  created_at timestamptz not null default now(),
  unique (comment_id, reporter_id)
);

create table moderation_actions (
  id uuid primary key,
  comment_id uuid not null references comments(id),
  moderator_id uuid not null,
  action varchar(32) not null,
  created_at timestamptz not null
);

create table newsletter_subscriptions (
  id uuid primary key,
  email varchar(320) not null unique,
  status varchar(32) not null,
  frequency varchar(32) not null,
  consent_source varchar(200) not null,
  consent_at timestamptz not null,
  verification_token_hash varchar(128) not null,
  unsubscribe_token_hash varchar(128) not null,
  verified_at timestamptz,
  unsubscribed_at timestamptz,
  version bigint not null default 0
);

create table consent_records (
  id uuid primary key,
  subscription_id uuid not null references newsletter_subscriptions(id),
  action varchar(32) not null,
  source varchar(200) not null,
  occurred_at timestamptz not null,
  ip_hash varchar(128)
);

create table newsletter_campaigns (
  id uuid primary key,
  campaign_key varchar(200) not null unique,
  article_id uuid references articles(id),
  campaign_type varchar(32) not null,
  created_at timestamptz not null
);

create table newsletter_deliveries (
  id uuid primary key,
  subscription_id uuid not null references newsletter_subscriptions(id),
  article_id uuid not null references articles(id),
  campaign_key varchar(200) not null,
  status varchar(32) not null,
  attempt_count integer not null,
  created_at timestamptz not null,
  delivered_at timestamptz,
  provider_message_id varchar(200),
  failure_code varchar(100),
  unique (subscription_id, campaign_key)
);

create table provider_webhooks (
  id uuid primary key,
  provider varchar(100) not null,
  external_event_id varchar(200) not null,
  signature_valid boolean not null,
  received_at timestamptz not null,
  processed_at timestamptz,
  payload_hash varchar(64) not null,
  unique (provider, external_event_id)
);

create table audit_records (
  id uuid primary key,
  actor_id uuid,
  action varchar(100) not null,
  target_type varchar(100) not null,
  target_id uuid,
  occurred_at timestamptz not null,
  metadata jsonb not null default '{}'::jsonb
);
create index idx_audit_target on audit_records(target_type, target_id, occurred_at desc);

create table outbox_events (
  id uuid primary key,
  event_type varchar(100) not null,
  aggregate_id uuid not null,
  correlation_id uuid not null,
  causation_id uuid,
  idempotency_key varchar(300) not null unique,
  envelope_json text not null,
  created_at timestamptz not null,
  published_at timestamptz,
  version bigint not null default 0
);
create index idx_outbox_unpublished on outbox_events(created_at) where published_at is null;

create table processed_events (
  event_id uuid not null,
  consumer_name varchar(150) not null,
  processed_at timestamptz not null,
  primary key (event_id, consumer_name)
);
