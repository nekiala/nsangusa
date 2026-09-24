alter table image_generations
  add column rendered_prompt text,
  add column prompt_version varchar(100),
  add column provider_request_id varchar(200);

alter table media_assets
  add column generation_id uuid references image_generations(id) deferrable initially deferred,
  add column variant_name varchar(20),
  add constraint ck_media_asset_variant check (
    (generation_id is null and variant_name is null)
    or (generation_id is not null and variant_name is not null
        and variant_name in ('hero', 'thumbnail', 'social'))
  );

create unique index idx_media_assets_generation_variant
  on media_assets(generation_id, variant_name)
  where generation_id is not null;

create index idx_image_generations_object_key on image_generations(object_key);

with generation_assets as (
  select generation.id as generation_id,
         generation.article_id,
         generation.object_key,
         row_number() over (
           partition by generation.object_key order by generation.created_at desc, generation.id
         ) as position
    from image_generations generation
), variant_assets as (
  select generation.generation_id, asset.id as asset_id, variant.name
    from generation_assets generation
    cross join (values ('hero'), ('thumbnail'), ('social')) variant(name)
    join media_assets asset
      on asset.article_id = generation.article_id
     and asset.object_key = case
       when variant.name = 'hero' then generation.object_key
       else regexp_replace(generation.object_key, '/hero-([^/]+)$', '/' || variant.name || '-\1')
     end
   where generation.position = 1
     and (variant.name = 'hero' or generation.object_key ~ '/hero-[^/]+$')
)
update media_assets asset
   set generation_id = variant.generation_id, variant_name = variant.name
  from variant_assets variant
 where asset.id = variant.asset_id;
