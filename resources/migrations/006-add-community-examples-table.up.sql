CREATE TABLE community_examples (id INTEGER PRIMARY KEY,
                                 group_id TEXT NOT NULL,
                                 artifact_id TEXT NOT NULL,
                                 version_low TEXT NOT NULL,
                                 version_high TEXT,
                                 namespace TEXT NOT NULL,
                                 var TEXT,
                                 example_data BLOB NOT NULL);
