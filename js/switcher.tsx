import { h, Component } from "preact";
import fuzzysort from "fuzzysort";
import { ResultsView, ResultViewComponent } from "./listselect";

export type CljdocProject = {
  group_id: string;
  artifact_id: string;
  version: string;
  project_id?: string;
};

function isSameProject(p1: CljdocProject, p2: CljdocProject): boolean {
  // I can't believe you have to do this
  return p1.group_id === p2.group_id && p1.artifact_id === p2.artifact_id; //&& p1.version == p2.version
}

function parseCljdocURI(uri: string): CljdocProject | void {
  const splitted = uri.split("/");
  if (splitted.length >= 5 && splitted[1] === "d") {
    return {
      group_id: splitted[2],
      artifact_id: splitted[3],
      version: splitted[4]
    };
  }
}

function trackProjectOpened() {
  const maxTrackedCount = 15;
  const project = parseCljdocURI(window.location.pathname);
  if (project) {
    var previouslyOpened: CljdocProject[] = JSON.parse(
      localStorage.getItem("previouslyOpened") || "[]"
    );
    // remove identical values
    previouslyOpened = previouslyOpened.filter(p => !isSameProject(p, project));
    previouslyOpened.push(project);
    // truncate from the front to not have localstorage grow too large
    if (previouslyOpened.length > maxTrackedCount) {
      previouslyOpened = previouslyOpened.slice(
        previouslyOpened.length - maxTrackedCount,
        previouslyOpened.length
      );
    }
    localStorage.setItem("previouslyOpened", JSON.stringify(previouslyOpened));
  }
}

const SwitcherSingleResultView: ResultViewComponent = props => {
  const { result, isSelected, selectResult } = props;
  const project =
    result.group_id === result.artifact_id
      ? result.group_id
      : result.group_id + "/" + result.artifact_id;
  const docsUri =
    "/d/" + result.group_id + "/" + result.artifact_id + "/" + result.version;
  return (
    <a className="no-underline black" href={docsUri} onMouseOver={selectResult}>
      <div
        className={
          isSelected
            ? "pa3 bb b--light-gray bg-light-blue"
            : "pa3 bb b--light-gray"
        }
      >
        <h4 className="dib ma0">
          #{project} <span className="ml2 gray normal">{result.version}</span>
        </h4>
        <a className="link blue ml2" href={docsUri}>
          view docs
        </a>
      </div>
    </a>
  );
};

type SwitcherProps = any;

type SwitcherState = {
  results: CljdocProject[];
  previouslyOpened: CljdocProject[];
  selectedIndex: number;
  show: boolean;
};

class Switcher extends Component<SwitcherProps, SwitcherState> {
  inputNode?: HTMLInputElement | null;
  backgroundNode?: HTMLDivElement | null;

  handleKeyDown(e: KeyboardEvent) {
    if (e.target === this.inputNode) {
      if (e.key === "ArrowUp") {
        e.preventDefault(); // prevents caret from moving in input field
        this.setState({
          selectedIndex: Math.max(this.state.selectedIndex - 1, 0)
        });
      } else if (e.key === "ArrowDown") {
        e.preventDefault(); // prevents caret from moving in input field
        this.setState({
          selectedIndex: Math.min(
            this.state.selectedIndex + 1,
            this.state.results.length - 1
          )
        });
      }
    }

    // If target is document body, trigger  on key `cmd+k` for MacOs `ctrl+k` otherwise
    let isMac = navigator.platform.toUpperCase().indexOf("MAC") >= 0;
    let switcherShortcut = false;

    if (e.key === "k") {
      if ((isMac && e.metaKey) || (!isMac && e.ctrlKey)) {
        e.preventDefault();
        switcherShortcut = true;
      }
    }

    if (switcherShortcut && e.target === document.body) {
      this.setState({ show: true, results: this.state.previouslyOpened });
    } else if (e.key === "Escape") {
      this.setState({ show: false, results: [] });
    }
  }

  handleInputKeyUp(e: KeyboardEvent) {
    if (e.key === "Enter") {
      const r = this.state.results[this.state.selectedIndex];
      window.location.href =
        "/d/" + r.group_id + "/" + r.artifact_id + "/" + r.version;
    }
  }

  updateResults(searchStr: string) {
    if (searchStr === "") {
      this.initializeState();
    } else {
      let fuzzysortOptions = {
        allowTypo: false,
        key: "project_id"
        //keys: ['group_id', 'artifact_id']
      };
      let results = fuzzysort.go(
        searchStr,
        this.state.previouslyOpened,
        fuzzysortOptions
      );
      this.setState({
        results: results.map(r => r.obj),
        selectedIndex: 0
      });
    }
  }

  constructor() {
    super();
    this.handleKeyDown = this.handleKeyDown.bind(this);
    this.handleInputKeyUp = this.handleInputKeyUp.bind(this);
    this.updateResults = this.updateResults.bind(this);
  }

  initializeState() {
    let previouslyOpened: CljdocProject[] = JSON.parse(
      localStorage.getItem("previouslyOpened") || "[]"
    );

    previouslyOpened.forEach(
      r =>
        (r.project_id =
          r.group_id === r.artifact_id
            ? r.group_id
            : r.group_id + "/" + r.artifact_id)
    );
    previouslyOpened.reverse();

    this.setState({
      // Store previously opened states in reversed order (latest first)
      previouslyOpened: previouslyOpened,
      // For the initial state (i.e. no search query) recent docsets
      results: previouslyOpened,
      selectedIndex: 0
    });
  }

  componentDidMount() {
    document.addEventListener("keydown", this.handleKeyDown);
    this.initializeState();
  }

  componentDidUpdate(
    _previousProps: SwitcherProps,
    previousState: SwitcherState,
    _previousContext: any
  ) {
    if (!previousState.show && this.state.show && this.inputNode) {
      this.inputNode.focus();
    }
  }

  render(_props: SwitcherProps, state: SwitcherState) {
    console.log(state.results);
    if (state.show) {
      return (
        <div
          className="bg-black-30 fixed top-0 right-0 bottom-0 left-0 sans-serif"
          ref={node => (this.backgroundNode = node)}
          onClick={(e: MouseEvent) =>
            e.target === this.backgroundNode
              ? this.setState({ show: false })
              : null
          }
        >
          <div className="mw7 center mt6 bg-white pa3 br2 shadow-3">
            <input
              placeholder="Jump to recently viewed docs..."
              className="pa2 w-100 br1 border-box b--blue ba input-reset"
              ref={node => (this.inputNode = node)}
              onKeyUp={(e: KeyboardEvent) => this.handleInputKeyUp(e)}
              onInput={(e: Event) => {
                const target = e.target as HTMLFormElement;
                this.updateResults(target.value);
              }}
            />
            {state.results.length > 0 ? (
              <ResultsView
                results={state.results}
                selectedIndex={state.selectedIndex}
                onMouseOver={index => this.setState({ selectedIndex: index })}
                resultView={SwitcherSingleResultView}
              />
            ) : null}
          </div>
        </div>
      );
    } else {
      return null;
    }
  }
}

export { trackProjectOpened, Switcher };
