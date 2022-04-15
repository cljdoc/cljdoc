CREATE TABLE clojars_stats
(
    date        text,
    group_id    text,
    artifact_id text,
    downloads   integer,
    constraint stats_keys unique (date, group_id, artifact_id)
)
