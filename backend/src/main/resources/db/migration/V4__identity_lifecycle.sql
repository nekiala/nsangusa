alter table users
  add column local_credentials_enabled boolean not null default true,
  add column failed_login_attempts integer not null default 0,
  add column failed_login_window_started_at timestamptz,
  add column locked_until timestamptz,
  add column last_login_at timestamptz;

alter table external_identities
  add column email_at_link varchar(320),
  add column last_login_at timestamptz;

alter table newsletter_subscriptions
  add column user_id uuid references users(id) on delete set null;

create unique index uq_newsletter_subscription_user
  on newsletter_subscriptions(user_id)
  where user_id is not null;

create index idx_users_locked_until
  on users(locked_until)
  where locked_until is not null;

create index idx_external_identities_user
  on external_identities(user_id, created_at);

create index idx_verification_tokens_cleanup
  on verification_tokens(expires_at, used_at);
