alter table ai_requests add column event_id uuid;
alter table ai_requests add column reserved_tokens bigint not null default 0;
create index idx_ai_requests_event_operation on ai_requests(event_id, operation, created_at desc);
create unique index idx_ai_requests_completed_event_operation
  on ai_requests(event_id, operation) where status in ('completed', 'blocked') and event_id is not null;
create unique index idx_ai_results_request on ai_results(request_id);
alter table ai_requests add constraint ck_ai_request_tokens
  check (input_tokens >= 0 and output_tokens >= 0 and reserved_tokens >= 0);

create function redact_editorial_source_results() returns trigger language plpgsql as $$
begin
  delete from ai_results result using ai_requests request, story_candidate_sources source
   where result.request_id = request.id
     and source.story_candidate_id = request.story_candidate_id
     and source.source_post_id = new.id;
  update ai_requests request
     set status = 'redacted', error_code = 'source_content_changed'
   where exists (
     select 1 from story_candidate_sources source
      where source.story_candidate_id = request.story_candidate_id
        and source.source_post_id = new.id
   );
  return new;
end;
$$;

create trigger trg_redact_editorial_source_results
after update of permitted_text, status on source_posts
for each row when (
  old.permitted_text is distinct from new.permitted_text or old.status is distinct from new.status
) execute function redact_editorial_source_results();
