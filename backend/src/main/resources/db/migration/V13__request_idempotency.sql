create table request_idempotency (
  actor_id uuid not null,
  idempotency_key varchar(200) not null check (idempotency_key collate "C" ~ '^[!-~]{8,200}$'),
  operation text not null check (length(btrim(operation)) > 0),
  request_hash varchar(64) not null check (request_hash ~ '^[0-9a-f]{64}$'),
  status varchar(16) not null check (status = 'succeeded'),
  result text,
  created_at timestamptz not null default current_timestamp,
  completed_at timestamptz not null default clock_timestamp(),
  primary key (actor_id, idempotency_key)
);
