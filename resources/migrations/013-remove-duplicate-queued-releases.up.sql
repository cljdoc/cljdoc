-- turf duplicate queued releases

DELETE from releases
WHERE build_id is null
and id not in
  (select max(id)
   from releases
   where build_id is null
   group by group_id, artifact_id, version);
