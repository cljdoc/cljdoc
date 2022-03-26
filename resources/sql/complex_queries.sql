-- :name sql-resolve-version-ids :? :*
-- For some reason putting WITH upfront instead of supplying (VALUES...) directly after IN is much more performant
WITH v_ents(gid,aid,name) AS (VALUES :tuple*:version-entities)
SELECT id, group_id, artifact_id, name FROM versions WHERE (group_id, artifact_id, name) IN (SELECT * from v_ents)

-- :name sql-get-namespaces :? :*
select version_id, name, meta from namespaces where (version_id) IN :tuple:version-ids

-- :name sql-get-vars :? :*
WITH pairs(id, ns) AS (VALUES :tuple*:ns-idents)
SELECT name, meta FROM vars WHERE (version_id, namespace) IN (SELECT * from pairs)
