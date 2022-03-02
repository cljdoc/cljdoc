CREATE TABLE versions
(
    id          bigint primary key,
    name        text not null,
    group_id    text not null,
    artifact_id text not null,
    meta        bytea,
    unique (group_id, artifact_id, name)
);
--;;
CREATE TABLE namespaces
(
    id         bigint not null primary key,
    name       text   not null,
    platform   text   not null,
    meta       bytea  not null,
    version_id bigint not null,
    foreign key (version_id) references versions (id),
    unique (version_id, platform, name)
);
--;;
CREATE TABLE vars
(
    id         bigint not null primary key,
    name       text   not null,
    platform   text   not null,
    namespace  text   not null,
    meta       bytea  not null,
    version_id bigint not null,
    foreign key (version_id) references versions (id),
    unique (version_id, platform, namespace, name)
);
