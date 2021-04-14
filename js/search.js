import { Component, render, h } from "preact";
import * as listSelect from "./listselect";

function debounced(delay, fn) {
  let timerId;
  return function (...args) {
    if (timerId) {
      clearTimeout(timerId);
    }
    timerId = setTimeout(() => {
      fn(...args);
      timerId = null;
    }, delay);
  };
}

function cleanSearchStr(str) {
  // replace square and curly brackets in case people copy from
  // Leiningen/Boot files or deps.edn
  return str.replace(/[\{\}\[\]\"]+/g, "");
}

const loadResults = (str, cb) => {
  if (!str) return;
  const uri = "/api/search?q=" + str; //+ "&format=json";
  fetch(uri)
    .then(response => response.json())
    .then(json => cb(json.results));
};

class SearchInput extends Component {
  onKeyDown(e) {
    if (e.which === 13) {
      this.props.onEnter();
    } else if (e.which === 27) {
      this.props.unfocus();
    } else if (e.which === 38) {
      // arrow up
      e.preventDefault(); // prevents caret from moving in input field
      this.props.onArrowUp();
    } else if (e.which === 40) {
      // arrow down
      e.preventDefault();
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

    return h("input", {
      autofocus: true,
      placeHolder: "NEW! Jump to docs...",
      defaultValue: props.initialValue,
      className: "pa2 w-100 br1 border-box b--blue ba input-reset",
      onFocus: e => props.focus(),
      onBlur: e => setTimeout(_ => props.unfocus(), 200),
      onKeyDown: e => this.onKeyDown(e),
      onInput: e =>
        debouncedLoader(
          cleanSearchStr(e.target.value),
          props.newResultsCallback
        )
    });
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

const SingleResultView = (r, isSelected, selectResult) => {
  const project =
    r["group-id"] === r["artifact-id"]
      ? r["group-id"]
      : r["group-id"] + "/" + r["artifact-id"];
  const docsUri = resultUri(r);
  const rowClass = isSelected
    ? "pa3 bb b--light-gray bg-light-blue"
    : "pa3 bb b--light-gray";
  return (
    <a class="no-underline black" href={docsUri}>
      <div class={rowClass} onMouseOver={selectResult}>
        <h4 class="dib ma0">
          {project}
          <span class="ml2 gray normal">{r.version}</span>
        </h4>
        <a class="link blue ml2" href={docsUri}>
          view docs
        </a>
      </div>
    </a>
  );
};

class App extends Component {
  constructor(props) {
    super(props);
    this.state = { results: [], focused: false, selectedIndex: 0 };
  }

  render(props, state) {
    function resultsView(selectResult) {
      return (
        <div
          class="bg-white br1 br--bottom bb bl br b--blue w-100 absolute"
          style="top: 2.3rem; box-shadow: 0 4px 10px rgba(0,0,0,0.1)"
        >
          <listSelect.ResultsView
            resultView={SingleResultView}
            results={state.results}
            selectedIndex={state.selectedIndex}
            onMouseOver={selectResult}
          />
        </div>
      );
    }

    return h("div", { className: "relative system-sans-serif" }, [
      h(SearchInput, {
        initialValue: this.props.initialValue,
        newResultsCallback: rs =>
          this.setState({ focused: true, results: rs, selectedIndex: 0 }),
        onEnter: () =>
          window.open(
            resultUri(this.state.results[this.state.selectedIndex]),
            "_self"
          ),
        onArrowUp: () =>
          this.setState({
            selectedIndex: Math.max(this.state.selectedIndex - 1, 0)
          }),
        onArrowDown: () =>
          this.setState({
            selectedIndex: Math.min(
              this.state.selectedIndex + 1,
              this.state.results.length - 1
            )
          }),
        focus: () => this.setState({ focused: true }),
        unfocus: () => this.setState({ focused: false })
      }),
      state.focused && state.results.length > 0
        ? resultsView(idx => this.setState({ selectedIndex: idx }))
        : null
    ]);
  }
}

export { App };
