-- Requires a caller-owned transaction and one recovery_input(document jsonb) row.
-- Only used on the isolated, paused restore by operational-drill.py.
do $$
begin
  if (select count(*) from recovery_input) <> 1 then
    raise exception 'Exactly one reviewed recovery ledger required';
  end if;
  if exists (
    select 1 from recovery_input,
      jsonb_populate_recordset(null::source_tombstones, document->'source_tombstones') t
    where not exists (select 1 from source_posts p where p.id=t.source_post_id
      and p.post_id=t.post_id and p.account_id=t.account_id)
  ) then
    raise exception 'Tombstone refers to an absent or changed source; manual reconciliation required';
  end if;
  if exists (
    select 1 from recovery_input,
      jsonb_populate_recordset(null::newsletter_deliveries, document->'newsletter_deliveries') n
    where not exists (select 1 from newsletter_deliveries d where d.id=n.id
      and d.provider_idempotency_key=n.provider_idempotency_key
      and d.subscription_id=n.subscription_id and d.campaign_key=n.campaign_key)
  ) then
    raise exception 'Newsletter ledger has unknown delivery identities; do not recreate/send';
  end if;
end $$;
insert into source_tombstones
select t.* from recovery_input,
  jsonb_populate_recordset(null::source_tombstones, document->'source_tombstones') t
on conflict (source_post_id) do nothing;
insert into event_replay_suppressions
select s.* from recovery_input,
  jsonb_populate_recordset(null::event_replay_suppressions, document->'event_replay_suppressions') s
on conflict (aggregate_id) do nothing;
update source_posts p set status='deleted', permitted_text='[deleted]',
  content_deleted_at=t.deleted_at, compliance_reason=t.reason
from source_tombstones t
where p.id=t.source_post_id and (p.status <> 'deleted' or p.permitted_text <> '[deleted]');
update source_posts p set status='excluded_restore', permitted_text='[source restricted]',
  compliance_reason=s.reason
from event_replay_suppressions s
where p.id=s.aggregate_id and p.status='active';
-- An article can be suppressed independently of a source tombstone.
update articles a set state='UNPUBLISHED', headline='[restricted]', summary='[restricted]',
  body='[restricted]', content=null, editorial_context=null, seo_title='[restricted]',
  seo_description='[restricted]', warnings='', tags='', correction_note=null,
  hero_object_key=null, image_alt_text=null, comments_enabled=false,
  unpublished_at=coalesce(a.unpublished_at, s.suppressed_at)
from event_replay_suppressions s where s.aggregate_id=a.id;
update article_revisions r set headline='[restricted]', summary='[restricted]', body='[restricted]',
  snapshot=null, ai_generation_result=null
where exists (select 1 from event_replay_suppressions s where s.aggregate_id=r.article_id);
delete from article_search_documents d where exists
  (select 1 from event_replay_suppressions s where s.aggregate_id=d.article_id);
update scheduled_publications p set status='cancelled' where status in ('scheduled','failed')
  and exists (select 1 from event_replay_suppressions s where s.aggregate_id=p.article_id);
update media_assets m set deleted_at=coalesce(m.deleted_at, s.suppressed_at)
from event_replay_suppressions s where s.aggregate_id=m.article_id;
update image_generations g set prompt='[restricted]', rendered_prompt=null, alt_text='[restricted]'
where exists (select 1 from event_replay_suppressions s where s.aggregate_id=g.article_id);
update outbox_events e set envelope_json='{"redacted":true,"reason":"source_restricted"}',
  published_at=coalesce(e.published_at, s.suppressed_at)
from event_replay_suppressions s where s.aggregate_id=e.aggregate_id;
update failed_events e set payload='{"redacted":true,"reason":"source_restricted"}', status='suppressed',
  exception_message=null
where exists (select 1 from event_replay_suppressions s where s.aggregate_id=e.aggregate_id);
update newsletter_deliveries d set
  status=case when n.status in ('delivered','bounced','complained') then n.status
              else 'reconciliation_required' end,
  delivered_at=n.delivered_at, provider_message_id=n.provider_message_id,
  delivery_confirmed_at=n.delivery_confirmed_at,
  attempt_count=greatest(d.attempt_count,n.attempt_count),
  attempt_token=null, failure_code=case when n.status='delivered' then n.failure_code
                                      else 'restored_backup_reconciliation' end
from recovery_input,
  jsonb_populate_recordset(null::newsletter_deliveries, document->'newsletter_deliveries') n
where d.id=n.id;
update newsletter_deliveries set status='reconciliation_required', attempt_token=null,
  failure_code='restored_backup_reconciliation'
where status in ('pending','failed','sending');
update newsletter_campaigns set status='reconciliation_required'
where status not in ('completed','reconciliation_required');
