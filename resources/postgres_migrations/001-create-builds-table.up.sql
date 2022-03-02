CREATE TABLE builds
(
    id                    bigint primary key,
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
    error_info            bytea
);
