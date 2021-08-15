import { Component, ComponentChildren, JSX } from "preact";
import { ResultsView } from "./listselect";

// Doing types for debouncing functions is really hard.
// https://gist.github.com/ca0v/73a31f57b397606c9813472f7493a940
function debounced<T extends (...args: any[]) => any>(
  delayInMs: number,
  callbackFn: T
): (...args: Parameters<T>) => Promise<ReturnType<T>> {
  let timerId: ReturnType<typeof setTimeout>;
  return function (...args: Parameters<T>) {
    if (timerId) {
      clearTimeout(timerId);
    }

    return new Promise(resolve => {
      timerId = setTimeout(() => {
        const returnValue = callbackFn(...args);
        resolve(returnValue);
      }, delayInMs);
    });
  };
}

function cleanSearchStr(str: string) {
  // replace square and curly brackets in case people copy from
  // Leiningen/Boot files or deps.edn
  return str.replace(/[\{\}\[\]\"]+/g, "");
}

export type SearchResult = {
  ["artifact-id"]: string;
  ["group-id"]: string;
  description: string;
  origin: string;
  version: string;
  score: number;
};

type SearchResults = {
  count: number;
  results: SearchResult[];
};

type LoadCallback = (sr: SearchResult[]) => any;

const loadResults = (str: string, cb: LoadCallback) => {
  if (!str) return;
  const uri = "/api/search?q=" + str; //+ "&format=json";
  fetch(uri)
    .then(response => response.json())
    .then((json: SearchResults) => cb(json.results));
};

type SearchInputProps = {
  onEnter: () => any;
  focus: () => any;
  unfocus: () => any;
  onArrowUp: () => any;
  onArrowDown: () => any;
  initialValue: string | null | undefined;
  newResultsCallback: LoadCallback;
};

class SearchInput extends Component<SearchInputProps> {
  onKeyDown(e: KeyboardEvent) {
    if (e.key === "Enter") {
      this.props.onEnter();
    } else if (e.key === "Escape") {
      this.props.unfocus();
    } else if (e.key === "ArrowUp") {
      e.preventDefault(); // prevents caret from moving in input field
      this.props.onArrowUp();
    } else if (e.key === "ArrowDown") {
      e.preventDefault(); // prevents caret from moving in input field
      this.props.onArrowDown();
    }
  }

  componentDidMount() {
    if (this.props.initialValue) {
      loadResults(
        cleanSearchStr(this.props.initialValue),
        this.props.newResultsCallback
      );
    }
  }

  render(props) {
    const debouncedLoader = debounced(300, loadResults);

    return (
      <input
        autofocus={true}
        placeHolder="NEW! Jump to docs..."
        defaultValue={props.initialValue}
        className="pa2 w-100 br1 border-box b--blue ba input-reset"
        onFocus={(_e: Event) => props.focus()}
        onBlur={(_e: Event) => setTimeout(_ => props.unfocus(), 200)}
        onKeyDown={(e: KeyboardEvent) => this.onKeyDown(e)}
        onInput={(e: InputEvent) => {
          const target = e.target as HTMLFormElement;
          debouncedLoader(
            cleanSearchStr(target.value),
            props.newResultsCallback
          );
        }}
      />
    );
  }
}

function resultUri(result) {
  return (
    "/d/" +
    result["group-id"] +
    "/" +
    result["artifact-id"] +
    "/" +
    result.version
  );
}

const SingleResultView = (props: {
  result: SearchResult;
  isSelected: boolean;
  selectResult: () => any;
}) => {
  const { result, isSelected, selectResult } = props;
  const project =
    result["group-id"] === result["artifact-id"]
      ? result["group-id"]
      : result["group-id"] + "/" + result["artifact-id"];
  const docsUri = resultUri(result);
  const rowClass = isSelected
    ? "pa3 bb b--light-gray bg-light-blue"
    : "pa3 bb b--light-gray";
  return (
    <a class="no-underline black" href={docsUri}>
      <div class={rowClass} onMouseOver={selectResult}>
        <h4 class="dib ma0">
          {project}
          <span class="ml2 gray normal">{result.version}</span>{" "}
        </h4>{" "}
        <a class="link blue ml2" href={docsUri}>
          view docs{" "}
        </a>{" "}
      </div>{" "}
    </a>
  );
};

type AppState = {
  results: SearchResult[];
  focused: boolean;
  selectedIndex: number;
};

type AppProps = AppState & {
  initialValue: string | null | undefined;
};

class App extends Component<AppProps, AppState> {
  constructor(props) {
    super(props);
    this.state = { results: [], focused: false, selectedIndex: 0 };
  }

  render(props: AppProps, state: AppState) {
    function resultsView(selectResult: (index: number) => any) {
      return (
        <div
          class="bg-white br1 br--bottom bb bl br b--blue w-100 absolute"
          style="top: 2.3rem; box-shadow: 0 4px 10px rgba(0,0,0,0.1)"
        >
          <ResultsView
            resultView={SingleResultView}
            results={state.results}
            selectedIndex={state.selectedIndex}
            onMouseOver={selectResult}
          />{" "}
        </div>
      );
    }

    return (
      <div className="relative system-sans-serif">
        <SearchInput
          initialValue={this.props.initialValue}
          newResultsCallback={rs =>
            this.setState({ focused: true, results: rs, selectedIndex: 0 })
          }
          onEnter={() => {
            const result = this.state.results[this.state.selectedIndex];

            if (result) {
              window.open(resultUri(result), "_self");
            }
          }}
          onArrowUp={() =>
            this.setState({
              selectedIndex: Math.max(this.state.selectedIndex - 1, 0)
            })
          }
          onArrowDown={() =>
            this.setState({
              selectedIndex: Math.min(
                this.state.selectedIndex + 1,
                this.state.results.length - 1
              )
            })
          }
          focus={() => {
            this.setState({ focused: true });
          }}
          unfocus={() => this.setState({ focused: false })}
        >
          {state.focused && state.results.length > 0
            ? resultsView((idx: number) =>
                this.setState({ selectedIndex: idx })
              )
            : null}
        </SearchInput>
      </div>
    );
  }
}

export { App };
