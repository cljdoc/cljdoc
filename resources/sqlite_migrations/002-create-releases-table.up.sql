CREATE TABLE releases (id INTEGER PRIMARY KEY ASC,
                       group_id NOT NULL,
                       artifact_id NOT NULL,
                       version NOT NULL,
                       created_ts NOT NULL,
                       build_id INTEGER,
                       FOREIGN KEY(build_id) REFERENCES builds(id));
