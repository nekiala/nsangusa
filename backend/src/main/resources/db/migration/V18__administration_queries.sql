alter table event_replay_requests
  add column confirmed_request_id uuid references event_replay_requests(id),
  add column next_replay_at timestamptz not null default now();
create unique index uq_replay_preview_confirmation
  on event_replay_requests(confirmed_request_id) where confirmed_request_id is not null;
create index idx_audit_inventory on audit_records(occurred_at desc, id desc);
create index idx_audit_action_inventory on audit_records(action, occurred_at desc, id desc);
create index idx_audit_actor_inventory on audit_records(actor_id, occurred_at desc, id desc);
create index idx_replay_inventory on event_replay_requests(requested_at desc, id desc);
create index idx_replay_due on event_replay_requests(next_replay_at, requested_at)
  where status in ('pending', 'processing');
