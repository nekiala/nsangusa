-- Newsletter emails follow each subscriber's language. Existing subscribers signed up in English.
alter table newsletter_subscriptions add column language varchar(8) not null default 'en';
