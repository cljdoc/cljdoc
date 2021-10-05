import { h, render } from "preact";
import { CljdocProject } from "./switcher";
import { formatDistanceToNowStrict } from "date-fns";

const dayInMs = 86400000;

const lastViewedMessage = (last_viewed: string | undefined) => {
  if (last_viewed) {
    const lastViewedDate = new Date(Date.parse(last_viewed)).getTime();
    const viewedToday = new Date().getTime() - lastViewedDate < dayInMs;
    return (
      " last viewed " +
      (viewedToday
        ? "today"
        : formatDistanceToNowStrict(new Date(Date.parse(last_viewed)), {
            addSuffix: true,
            unit: "day"
          }))
    );
  } else {
    return "";
  }
};

export const initRecentDocLinks = (docLinks: Element) => {
  const previouslyOpened: Array<CljdocProject> = JSON.parse(
    localStorage.getItem("previouslyOpened") || "[]"
  );
  if (previouslyOpened.length > 0) {
    render(
      <p className="mt4 mb0">
        <div className="fw5">Pick up where you left off:</div>
        <ul className="mv0 pl0 list">
          {previouslyOpened
            .reverse()
            .slice(0, 3)
            .map(({ group_id, artifact_id, version, last_viewed }) => {
              return (
                <li className="mr1 pt2">
                  <a
                    className="link blue nowrap"
                    href={`/d/${group_id}/${artifact_id}/${version}`}
                  >
                    {artifact_id}
                  </a>
                  <span className="gray f6">
                    {lastViewedMessage(last_viewed)}
                  </span>
                </li>
              );
            })}
        </ul>
      </p>,
      docLinks
    );
  }
};
