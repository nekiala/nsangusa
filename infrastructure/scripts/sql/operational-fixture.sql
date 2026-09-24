insert into monitored_x_accounts(id, account_id, handle, display_name, topics,
  relevance_threshold, created_at, last_successful_sync_at)
values ('10000000-0000-0000-0000-000000000001', 'synthetic', 'synthetic', 'Synthetic only',
  'test', 0.5, '2026-01-01Z', '2026-01-01Z');
insert into source_posts(id, monitored_account_id, post_id, account_id, handle, canonical_url,
  permitted_text, published_at, ingested_at, status)
select ('20000000-0000-0000-0000-00000000000' || n)::uuid,
  '10000000-0000-0000-0000-000000000001', 'synthetic-' || n, 'synthetic', 'synthetic',
  'https://example.invalid/source/' || n, 'synthetic source ' || n,
  '2026-01-01Z', '2026-01-01Z', 'active'
from generate_series(1,2) n;
insert into story_candidates(id, primary_source_post_id, topic, status, created_at)
values ('30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001',
  'test', 'completed', '2026-01-01Z');
insert into articles(id, slug, headline, summary, body, seo_title, seo_description, topic, tags,
  state, confidence, warnings, created_at, updated_at, published_at, content)
select ('40000000-0000-0000-0000-00000000000' || n)::uuid,
  case when n=1 then 'restricted-synthetic' else 'retained-synthetic' end,
  'synthetic headline', 'synthetic summary', 'synthetic body', 'synthetic', 'synthetic',
  'test', '', 'PUBLISHED', 0.9, '', '2026-01-01Z', '2026-01-01Z', '2026-01-01Z',
  '{"version":1,"blocks":[{"type":"paragraph","text":"synthetic body"}]}'::jsonb
from generate_series(1,2) n;
insert into article_sources(id, article_id, source_post_id, account, post_id, url, published_at)
select ('50000000-0000-0000-0000-00000000000' || n)::uuid,
  ('40000000-0000-0000-0000-00000000000' || n)::uuid,
  ('20000000-0000-0000-0000-00000000000' || n)::uuid, 'synthetic', 'synthetic-' || n,
  'https://example.invalid/source/' || n, '2026-01-01Z'
from generate_series(1,2) n;
insert into article_revisions(id, article_id, revision_number, headline, summary, body, reason, created_at,
  snapshot)
values ('60000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', 1,
  'synthetic headline', 'synthetic summary', 'synthetic body', 'CREATED', '2026-01-01Z',
  '{"sources":[{"sourcePostId":"20000000-0000-0000-0000-000000000001"}]}'::jsonb);
insert into media_assets(id, article_id, object_key, media_type, created_at)
values ('70000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001',
  'restricted.txt', 'text/plain', '2026-01-01Z');
insert into outbox_events(id,event_type,aggregate_id,correlation_id,idempotency_key,envelope_json,created_at)
values ('80000000-0000-0000-0000-000000000001', 'ArticlePublished',
  '40000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001',
  'synthetic-restricted', '{"synthetic":true}', '2026-01-01Z');
insert into processed_events(event_id,consumer_name,processed_at)
values ('80000000-0000-0000-0000-000000000001', 'synthetic-consumer', '2026-01-01Z');
insert into newsletter_subscriptions(id,email,status,frequency,consent_source,consent_at,
  verification_token_hash,unsubscribe_token_hash,verified_at)
values ('90000000-0000-0000-0000-000000000001', 'synthetic@example.invalid', 'active', 'immediate',
  'synthetic-drill-not-legal-consent', '2026-01-01Z', 'synthetic', 'synthetic', '2026-01-01Z');
insert into newsletter_deliveries(id,subscription_id,article_id,campaign_key,status,attempt_count,
  created_at,provider_idempotency_applied,attempt_token,attempt_started_at)
values ('a0000000-0000-0000-0000-000000000001', '90000000-0000-0000-0000-000000000001',
  '40000000-0000-0000-0000-000000000001', 'synthetic-campaign', 'pending', 1, '2026-01-01Z',
  true, 'a0000000-0000-0000-0000-000000000002', '2026-01-01Z');
