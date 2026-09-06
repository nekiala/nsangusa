alter table image_generations
  add column approved_at timestamptz,
  add column approved_by uuid;

create index idx_image_generations_article_created
  on image_generations(article_id, created_at desc);
