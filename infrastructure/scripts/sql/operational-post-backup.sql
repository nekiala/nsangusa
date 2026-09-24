begin;
insert into source_tombstones(id,source_post_id,post_id,account_id,reason,deleted_at)
values ('b0000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001',
  'synthetic-1', 'synthetic', 'synthetic deletion replay', '2026-01-02Z');
update source_posts set status='deleted', permitted_text='[deleted]',
  content_deleted_at='2026-01-02Z', compliance_reason='synthetic deletion replay'
where id='20000000-0000-0000-0000-000000000001';
insert into event_replay_suppressions(aggregate_id,reason,suppressed_at)
values ('40000000-0000-0000-0000-000000000002', 'synthetic article-only suppression', '2026-01-02Z');
update newsletter_deliveries set status='delivered', delivered_at='2026-01-02Z',
  provider_message_id='synthetic-provider-receipt-no-message-sent'
where id='a0000000-0000-0000-0000-000000000001';
-- Deliberately outside the recovery point; recovery must report loss, not invent the event.
insert into outbox_events(id,event_type,aggregate_id,correlation_id,idempotency_key,envelope_json,created_at)
values ('80000000-0000-0000-0000-000000000002', 'ArticleUpdated',
  '40000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000002',
  'synthetic-after-backup', '{"synthetic":true}', '2026-01-02Z');
commit;
