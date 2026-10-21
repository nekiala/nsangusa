-- Articles gain a primary language and reviewed translations. Existing articles were written in English.
alter table articles add column language varchar(8) not null default 'en';

create table article_translations (
  id uuid primary key,
  article_id uuid not null references articles(id) on delete cascade,
  language varchar(8) not null,
  headline varchar(300) not null,
  summary text not null,
  body text not null,
  editorial_context text,
  seo_title varchar(300) not null,
  seo_description varchar(500) not null,
  image_alt_text varchar(500),
  updated_at timestamptz not null,
  constraint article_translations_language_key unique (article_id, language)
);
