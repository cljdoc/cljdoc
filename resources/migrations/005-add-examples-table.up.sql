CREATE TABLE examples (id INTEGER PRIMARY KEY,
                       version_id INTEGER NOT NULL,
                       -- TODO consider using one field, detect via existance of slash
                       namespace TEXT,
                       var TEXT,
                       content BLOB NOT NULL);
