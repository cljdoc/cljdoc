create table clojars_stats (
  date        TEXT,
  group_id    TEXT,
  artifact_id TEXT,
  version     TEXT,
  downloads   INT,
  CONSTRAINT stats_keys UNIQUE (date, group_id, artifact_id, version)
)
