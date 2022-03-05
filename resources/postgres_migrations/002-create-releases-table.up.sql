CREATE TABLE releases
(
    id          serial primary key,
    group_id    text        not null,
    artifact_id text        not null,
    version     text        not null,
    created_ts  timestamptz not null,
    build_id    integer,
    foreign key (build_id) references builds (id)
);
