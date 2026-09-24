create table ai_provider_settings (
  version bigint primary key check (version > 0),
  provider varchar(100) not null check (provider in ('fake', 'openai')),
  model varchar(100) not null,
  prompt_version varchar(100) not null references ai_prompt_versions(version),
  timeout_seconds integer not null check (timeout_seconds between 1 and 120),
  max_output_tokens integer not null check (max_output_tokens between 256 and 16384),
  daily_token_budget bigint not null check (daily_token_budget between 1 and 1000000000),
  changed_by uuid not null,
  changed_at timestamptz not null default current_timestamp,
  reason varchar(500) not null
);

create trigger ai_provider_settings_immutable before update or delete on ai_provider_settings
  for each row execute function preserve_ai_administration_history();

create table ai_provider_setup (
  id integer primary key check (id = 1),
  version bigint not null default 0,
  draft_version bigint references ai_provider_settings(version),
  active_version bigint references ai_provider_settings(version),
  live_active boolean not null default false,
  credential_id uuid,
  credential_ciphertext text,
  check ((credential_id is null) = (credential_ciphertext is null))
);

insert into ai_provider_setup(id) values (1);
