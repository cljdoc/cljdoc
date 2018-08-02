CREATE TABLE groups (id TEXT NOT NULL PRIMARY KEY);
--;;
CREATE TABLE artifacts (id TEXT NOT NULL,
                        group_id TEXT NOT NULL,
                        FOREIGN KEY (group_id) REFERENCES groups(id),
                        PRIMARY KEY (group_id, id));
--;;
CREATE TABLE versions (id INTEGER PRIMARY KEY,
                       name TEXT NOT NULL,
                       group_id TEXT NOT NULL,
                       artifact_id TEXT NOT NULL,
                       meta BLOB,
                       FOREIGN KEY (group_id) REFERENCES groups(id),
                       FOREIGN KEY (group_id, artifact_id) REFERENCES artifacts(group_id, id),
                       UNIQUE (group_id, artifact_id, name)); --- ON CONFLICT REPLACE)
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
