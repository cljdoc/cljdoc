import { h, render } from "preact";
import { CljdocProject } from "./switcher";

export const initRecentDocLinks = (docLinks: Element) => {
  const previouslyOpened: Array<CljdocProject> = JSON.parse(
    localStorage.getItem("previouslyOpened") || "[]"
  );

  const originalLinks: Array<String> = [];

  for (const child of Array.from(docLinks.children)) {
    originalLinks.push(child.innerHTML);
  }

  previouslyOpened
    .reverse()
    .slice(0, 3)
    .forEach(
      ({ group_id, artifact_id, version }, i) =>
        !originalLinks.includes(artifact_id) &&
        render(
          <a
            className="link blue nowrap"
            href={`/d/${group_id}/${artifact_id}/${version}`}
          >
            {artifact_id}
          </a>,
          docLinks,
          docLinks.children[i]
        )
    );
};
