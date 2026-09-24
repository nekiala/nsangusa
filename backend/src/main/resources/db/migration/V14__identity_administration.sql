alter table users
  add column authentication_valid_after timestamptz,
  add column profile_updated_at timestamptz,
  add column roles_managed_locally boolean not null default false;

alter table user_roles
  add constraint ck_identity_allowed_role
    check (role in ('READER', 'MODERATOR', 'EDITOR', 'ADMINISTRATOR'));

create index idx_identity_users_created on users(created_at desc, id);
create index idx_identity_users_email_lower on users(lower(email));
create index idx_identity_roles_role on user_roles(role, user_id);

-- A single transaction lock serializes administrator changes and account deletion.
create table identity_administration_guard (
  id integer primary key check (id = 1)
);
insert into identity_administration_guard(id) values (1);
