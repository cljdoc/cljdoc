export interface Library {
  group_id: string;
  artifact_id: string;
  version: string;
}

export function docsUri(library: Library) {
  return (
    "/d/" + library.group_id + "/" + library.artifact_id + "/" + library.version
  );
}

export function project(library: Library) {
  return (
    library.group_id === library.artifact_id
      ? library.group_id
      : library.group_id + "/" + library.artifact_id
  );
}
