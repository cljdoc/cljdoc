CREATE TABLE versions (id INTEGER PRIMARY KEY,
                       name TEXT NOT NULL,
                       group_id TEXT NOT NULL,
                       artifact_id TEXT NOT NULL,
                       meta BLOB,
                       UNIQUE (group_id, artifact_id, name));
--;;
CREATE TABLE namespaces (id INTEGER NOT NULL PRIMARY KEY,
                         name TEXT NOT NULL,
                         platform TEXT NOT NULL,
                         meta BLOB NOT NULL,
                         version_id INTEGER NOT NULL,
                         FOREIGN KEY (version_id) REFERENCES versions(id),
                         UNIQUE (version_id, platform, name));
--;;
CREATE TABLE vars (id INTEGER NOT NULL PRIMARY KEY,
                   name TEXT NOT NULL,
                   platform TEXT NOT NULL,
                   namespace TEXT NOT NULL,
                   meta BLOB NOT NULL,
                   version_id INTEGER NOT NULL,
                   FOREIGN KEY (version_id) REFERENCES versions(id),
                   UNIQUE (version_id, platform, namespace, name));
