CREATE TABLE builds
(
    id                    serial primary key,
    group_id              text        not null,
    artifact_id           text        not null,
    version               text        not null,
    analyzer_version      text,
    analysis_requested_ts timestamptz not null,
    analysis_triggered_ts timestamptz,
    analysis_job_uri      text,
    analysis_received_ts  timestamptz,
    analysis_result_uri   text,
    scm_url               text,
    commit_sha            text,
    import_completed_ts   timestamptz,
    api_imported_ts       timestamptz,
    git_imported_ts       timestamptz,
    git_problem           text,
    namespaces_count      integer,
    error                 text,
    error_info_map        bytea
);
--;;
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
--;;
CREATE TABLE versions
(
    id          serial primary key,
    name        text not null,
    group_id    text not null,
    artifact_id text not null,
    meta        bytea,
    unique (group_id, artifact_id, name)
);
--;;
CREATE TABLE namespaces
(
    id         serial not null primary key,
    name       text   not null,
    platform   text   not null,
    meta       bytea  not null,
    version_id serial not null,
    foreign key (version_id) references versions (id),
    unique (version_id, platform, name)
);
--;;
CREATE TABLE vars
(
    id         serial not null primary key,
    name       text   not null,
    platform   text   not null,
    namespace  text   not null,
    meta       bytea  not null,
    version_id serial not null,
    foreign key (version_id) references versions (id),
    unique (version_id, platform, namespace, name)
);
--;;
CREATE INDEX builds_artifact_index ON builds (group_id, artifact_id, version);
--;;
CREATE TABLE clojars_stats
(
    date        text,
    group_id    text,
    artifact_id text,
    downloads   integer,
    constraint stats_keys unique (date, group_id, artifact_id)
);
