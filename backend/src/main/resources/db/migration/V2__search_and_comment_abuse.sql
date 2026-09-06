create table article_search_documents (
  article_id uuid primary key,
  slug varchar(250) not null unique,
  headline varchar(300) not null,
  summary text not null,
  body text not null,
  topic varchar(100) not null,
  tags varchar(1000) not null,
  published_at timestamptz not null,
  updated_at timestamptz not null,
  search_vector tsvector generated always as (
    setweight(to_tsvector('english'::regconfig, coalesce(headline, '')), 'A') ||
    setweight(to_tsvector('english'::regconfig, coalesce(summary, '')), 'B') ||
    setweight(to_tsvector('english'::regconfig, coalesce(topic, '')), 'B') ||
    setweight(to_tsvector('english'::regconfig, coalesce(tags, '')), 'B') ||
    setweight(to_tsvector('english'::regconfig, coalesce(body, '')), 'C')
  ) stored
);

create index idx_article_search_vector
  on article_search_documents using gin(search_vector);
create index idx_article_search_topic_published
  on article_search_documents(topic, published_at desc);
create index idx_article_search_tags
  on article_search_documents using gin((string_to_array(tags, ',')));
create index idx_article_search_published
  on article_search_documents(published_at desc);

insert into article_search_documents
  (article_id, slug, headline, summary, body, topic, tags, published_at, updated_at)
select id, slug, headline, summary, body, lower(trim(topic)), lower(tags), published_at, updated_at
from articles
where state = 'PUBLISHED' and published_at is not null;

alter table comments
  add column updated_at timestamptz,
  add column edited_at timestamptz,
  add column deleted_by_author boolean not null default false,
  add column spam_score double precision not null default 0,
  add column spam_reason varchar(1000);

update comments set updated_at = created_at where updated_at is null;
alter table comments alter column updated_at set not null;

alter table comment_reports
  add column status varchar(32) not null default 'open',
  add column resolved_at timestamptz,
  add column resolved_by uuid,
  add column version bigint not null default 0,
  add constraint chk_comment_report_status check (status in ('open', 'resolved'));

create index idx_comment_reports_status_created
  on comment_reports(status, created_at);
create index idx_comment_reports_comment_status
  on comment_reports(comment_id, status);

alter table moderation_actions
  add column previous_state varchar(32),
  add column reason text;

create index idx_moderation_actions_comment_created
  on moderation_actions(comment_id, created_at desc);

create table commenting_privileges (
  user_id uuid primary key references users(id),
  status varchar(32) not null,
  suspended_until timestamptz,
  reason text,
  moderator_id uuid,
  updated_at timestamptz not null,
  version bigint not null default 0,
  constraint chk_commenting_privilege_status check (status in ('allowed', 'suspended'))
);

create index idx_commenting_privileges_status_until
  on commenting_privileges(status, suspended_until);

create table commenting_privilege_records (
  id uuid primary key,
  user_id uuid not null references users(id),
  action varchar(32) not null,
  suspended_until timestamptz,
  reason text not null,
  moderator_id uuid not null,
  created_at timestamptz not null,
  constraint chk_commenting_privilege_action check (action in ('suspended', 'restored'))
);

create index idx_commenting_privilege_records_user_created
  on commenting_privilege_records(user_id, created_at desc);

create table comment_settings (
  id integer primary key,
  enabled boolean not null,
  require_approval boolean not null,
  editing_window_minutes integer not null,
  review_spam_threshold double precision not null,
  reject_spam_threshold double precision not null,
  report_escalation_threshold integer not null,
  updated_at timestamptz not null,
  updated_by uuid,
  version bigint not null default 0,
  constraint chk_comment_settings_singleton check (id = 1),
  constraint chk_comment_edit_window check (editing_window_minutes between 0 and 10080),
  constraint chk_comment_spam_thresholds check (
    review_spam_threshold between 0 and 1
    and reject_spam_threshold between 0 and 1
    and review_spam_threshold <= reject_spam_threshold
  ),
  constraint chk_comment_report_threshold check (report_escalation_threshold between 1 and 100)
);

create table article_comment_settings (
  article_id uuid primary key references articles(id) on delete cascade,
  enabled_override boolean,
  require_approval_override boolean,
  updated_at timestamptz not null,
  updated_by uuid,
  version bigint not null default 0
);
