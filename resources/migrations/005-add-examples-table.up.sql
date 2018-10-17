CREATE TABLE examples (id INTEGER PRIMARY KEY,
                       version_id INTEGER NOT NULL,
                       namespace TEXT NOT NULL,
                       var TEXT,
                       example_data BLOB NOT NULL);
