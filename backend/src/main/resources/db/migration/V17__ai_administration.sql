create table ai_prompt_versions (
  revision bigint primary key check (revision > 0),
  version varchar(100) not null unique,
  guidance text not null check (length(guidance) between 10 and 4000),
  created_by uuid,
  created_at timestamptz not null default current_timestamp,
  reason varchar(500) not null
);

insert into ai_prompt_versions (revision, version, guidance, reason)
values (1, 'editorial-v1',
  'Use concise, neutral language. Attribute reported claims and make uncertainty explicit.',
  'Built-in editorial guidance; non-editable system safety rules remain authoritative');

create table ai_configuration_versions (
  version bigint primary key check (version > 0),
  provider varchar(100) not null,
  model varchar(100) not null,
  prompt_version varchar(100) not null references ai_prompt_versions(version),
  secret_reference varchar(200) not null,
  changed_by uuid not null,
  changed_at timestamptz not null default current_timestamp,
  reason varchar(500) not null
);

create function preserve_ai_administration_history() returns trigger language plpgsql as $$
begin
  raise exception 'AI configuration and prompt versions are immutable';
end;
$$;

create trigger ai_prompt_versions_immutable before update or delete on ai_prompt_versions
  for each row execute function preserve_ai_administration_history();
create trigger ai_configuration_versions_immutable before update or delete on ai_configuration_versions
  for each row execute function preserve_ai_administration_history();

alter table ai_requests
  add column configuration_version bigint,
  add column requested_model varchar(100),
  add column prompt_guidance text,
  add column secret_reference varchar(200);

create index ai_request_first_snapshot on ai_requests(event_id, operation, created_at);
