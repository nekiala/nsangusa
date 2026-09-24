create or replace function select_approved_image_generation()
returns trigger
language plpgsql
as $$
begin
  if new.safety_status = 'approved'
     and old.safety_status is distinct from new.safety_status then
    update articles
       set approved_image_generation_id = new.id,
           pending_image_generation_id =
             case when pending_image_generation_id = new.id then null
                  else pending_image_generation_id end,
           hero_object_key = new.object_key,
           image_alt_text = new.alt_text,
           generated_image = not (
             new.provider = 'nsangusa-editorial'
             and new.model = 'neutral-illustration-v1'
           ),
           image_approval_required = false,
           state =
             case
               when new.approval_event_expected = false and state = 'DRAFTING'
                 then 'AWAITING_REVIEW'
               else state
             end,
           version = version + 1,
           updated_at = now()
     where id = new.article_id
       and state not in ('ARCHIVED', 'REJECTED');
    if not found then
      raise exception 'Rejected or archived article % cannot select an image', new.article_id
        using errcode = '23514';
    end if;
  end if;
  return new;
end;
$$;
