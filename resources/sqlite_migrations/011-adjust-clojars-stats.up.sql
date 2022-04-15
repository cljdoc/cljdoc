-- We don't need track downloads to version granularity
-- Drop column support in sqlite is only suppported for more recent versions,
-- so recreate this unused table
DROP TABLE clojars_stats;
--;;
CREATE TABLE clojars_stats (
  date        TEXT,
  group_id    TEXT,
  artifact_id TEXT,
  downloads   INT,
  CONSTRAINT stats_keys UNIQUE (date, group_id, artifact_id)
);
