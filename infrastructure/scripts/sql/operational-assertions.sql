do $$
begin
  if not exists (select 1 from source_posts where id='20000000-0000-0000-0000-000000000001'
    and status='deleted' and permitted_text='[deleted]') then
    raise exception 'Deleted source resurrected';
  end if;
  if (select count(*) from articles where state='UNPUBLISHED' and content is null
      and body='[restricted]') <> 2 then
    raise exception 'Tombstoned or independently suppressed article resurrected';
  end if;
  if exists (select 1 from article_revisions where snapshot is not null or body <> '[restricted]') then
    raise exception 'Revision content resurrected';
  end if;
  if exists (select 1 from story_candidate_sources
      where source_post_id='20000000-0000-0000-0000-000000000001'
      and normalized_text <> '[source restricted]') then
    raise exception 'Story source content resurrected';
  end if;
  if (select count(*) from source_tombstones) <> 1
      or (select count(*) from processed_events) <> 1 then
    raise exception 'Durable tombstone or consumer receipt lost';
  end if;
  if exists (select 1 from outbox_events e join event_replay_suppressions s
      on s.aggregate_id=e.aggregate_id where e.published_at is null) then
    raise exception 'Suppressed outbox event remains deliverable';
  end if;
  if not exists (select 1 from newsletter_deliveries where status='delivered'
      and provider_message_id='synthetic-provider-receipt-no-message-sent' and attempt_token is null)
      or exists (select 1 from newsletter_deliveries where status in ('pending','failed','sending')) then
    raise exception 'Newsletter receipt missing or unsafe retry available';
  end if;
  if exists (select 1 from outbox_events where idempotency_key='synthetic-after-backup') then
    raise exception 'Recovery invented lost post-backup work';
  end if;
  begin
    update articles set state='PUBLISHED' where id='40000000-0000-0000-0000-000000000001';
    raise exception 'Restricted publication was allowed';
  exception when check_violation then null;
  end;
  begin
    update articles set state='PUBLISHED' where id='40000000-0000-0000-0000-000000000002';
    raise exception 'Independently suppressed publication was allowed';
  exception when check_violation then null;
  end;
end $$;
