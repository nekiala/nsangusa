begin;
insert into operational_retention_policies(id,data_class,retain_seconds,approval_reference,
  approved_by,approved_at,expires_at,enabled)
values ('c0000000-0000-0000-0000-000000000001','published_outbox_payload',1,
  'SYNTHETIC-DRILL-NOT-LEGAL-APPROVAL','synthetic-operator',now()-interval '1 day',
  now()+interval '1 day',false),
  ('c0000000-0000-0000-0000-000000000002','suppressed_failure_payload',1,
  'SYNTHETIC-DRILL-NOT-LEGAL-APPROVAL','synthetic-operator',now()-interval '1 day',
  now()+interval '1 day',false);
insert into outbox_events(id,event_type,aggregate_id,correlation_id,idempotency_key,envelope_json,
  created_at,published_at)
select ('d0000000-0000-0000-0000-00000000000'||n)::uuid,'Synthetic',
  ('e0000000-0000-0000-0000-00000000000'||n)::uuid,
  'e0000000-0000-0000-0000-000000000001','retention-fixture-'||n,'{"held":true}',
  '2020-01-01Z',case when n=3 then null else '2020-01-01Z'::timestamptz end
from generate_series(1,3) n;
insert into failed_events(id,dlt_topic,dlt_partition,dlt_offset,original_topic,original_partition,
  original_offset,event_id,payload,exception_message,status,failed_at,last_updated_at)
select ('d1000000-0000-0000-0000-00000000000'||n)::uuid,'synthetic.dlt',0,n,
  'synthetic',0,n,('e1000000-0000-0000-0000-00000000000'||n)::uuid,
  '{"held":true}','synthetic sensitive detail',
  case when n=3 then 'eligible' else 'suppressed' end,'2020-01-01Z','2020-01-01Z'
from generate_series(1,4) n;
insert into operational_legal_holds(id,record_id,reference)
values ('f0000000-0000-0000-0000-000000000001','e0000000-0000-0000-0000-000000000001',
  'SYNTHETIC-HOLD-NOT-LEGAL-APPROVAL'),
  ('f0000000-0000-0000-0000-000000000002','d1000000-0000-0000-0000-000000000001',
  'SYNTHETIC-HOLD-NOT-LEGAL-APPROVAL'),
  ('f0000000-0000-0000-0000-000000000004','e1000000-0000-0000-0000-000000000004',
  'SYNTHETIC-HOLD-NOT-LEGAL-APPROVAL');
do $$
declare evidence jsonb;
begin
  begin
    perform apply_operational_retention('c0000000-0000-0000-0000-000000000001',1,false);
    raise exception 'Disabled policy accepted' using errcode='23514';
  exception when raise_exception then null;
  end;
  update operational_retention_policies set enabled=true;
  evidence := apply_operational_retention('c0000000-0000-0000-0000-000000000001',1,true);
  if evidence->>'changed' <> '0' or evidence->>'selected' <> '1' then
    raise exception 'Dry run or bound failed';
  end if;
  evidence := apply_operational_retention('c0000000-0000-0000-0000-000000000001',1,false);
  if evidence->>'changed' <> '1' then raise exception 'Expected one redaction'; end if;
  if (select envelope_json from outbox_events where idempotency_key='retention-fixture-1')
      <> '{"held":true}' then raise exception 'Aggregate legal hold bypassed'; end if;
  if (select envelope_json from outbox_events where idempotency_key='retention-fixture-3')
      <> '{"held":true}' then raise exception 'Pending work redacted'; end if;
  if (select envelope_json from outbox_events where idempotency_key='retention-fixture-2')
      <> '{"redacted":true,"reason":"retention"}' then raise exception 'Eligible payload not redacted'; end if;
  evidence := apply_operational_retention('c0000000-0000-0000-0000-000000000002',1,false);
  if evidence->>'changed' <> '1' then raise exception 'Expected one suppressed failure redaction'; end if;
  if (select payload from failed_events where id='d1000000-0000-0000-0000-000000000001')
      <> '{"held":true}' then raise exception 'Failure record hold bypassed'; end if;
  if (select payload from failed_events where id='d1000000-0000-0000-0000-000000000003')
      <> '{"held":true}' then raise exception 'Replayable failure redacted'; end if;
  if exists (select 1 from failed_events where id='d1000000-0000-0000-0000-000000000002'
      and (payload <> '{"redacted":true,"reason":"retention"}' or exception_message is not null)) then
    raise exception 'Suppressed failure payload or error not redacted';
  end if;
  evidence := apply_operational_retention('c0000000-0000-0000-0000-000000000002',1,false);
  if evidence->>'selected' <> '0' or exists (
    select 1 from failed_events where id='d1000000-0000-0000-0000-000000000004'
      and (payload <> '{"held":true}' or exception_message is distinct from 'synthetic sensitive detail')
  ) then
    raise exception 'Original event-ID legal hold bypassed';
  end if;
  update operational_legal_holds set released_at=now()
    where id='f0000000-0000-0000-0000-000000000004';
  evidence := apply_operational_retention('c0000000-0000-0000-0000-000000000002',1,false);
  if evidence->>'changed' <> '1' or exists (
    select 1 from failed_events where id='d1000000-0000-0000-0000-000000000004'
      and (payload <> '{"redacted":true,"reason":"retention"}' or exception_message is not null)
  ) then
    raise exception 'Released event-ID hold still prevented redaction';
  end if;
  insert into operational_legal_holds(id,reference)
    values ('f0000000-0000-0000-0000-000000000003','SYNTHETIC-GLOBAL-HOLD');
  evidence := apply_operational_retention('c0000000-0000-0000-0000-000000000001',100,false);
  if evidence->>'selected' <> '0' then raise exception 'Global legal hold bypassed'; end if;
  update operational_legal_holds set released_at=now()
    where id='f0000000-0000-0000-0000-000000000003';
  begin
    perform apply_operational_retention('c0000000-0000-0000-0000-000000000001',1001,false);
    raise exception 'Unbounded batch accepted' using errcode='23514';
  exception when raise_exception then null;
  end;
  begin
    perform apply_operational_retention('c0000000-0000-0000-0000-000000000001',1,null);
    raise exception 'Implicit dry run accepted' using errcode='23514';
  exception when raise_exception then null;
  end;
  begin
    perform apply_operational_retention('c0000000-0000-0000-0000-000000000099',1,false);
    raise exception 'Missing policy accepted' using errcode='23514';
  exception when no_data_found then null;
  end;
  update operational_retention_policies set expires_at=now()-interval '1 second';
  begin
    perform apply_operational_retention('c0000000-0000-0000-0000-000000000001',1,false);
    raise exception 'Expired policy accepted' using errcode='23514';
  exception when raise_exception then null;
  end;
end $$;
rollback;
