import { Component, render, h } from "preact";

function debounced(delay, fn) {
  let timerId;
  return function(...args) {
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
  const uri = "https://clojars.org/search?q=" + str + "&format=json";
  fetch(uri)
    .then(response => response.json())
    .then(json => cb(json.results));
};

class SearchInput extends Component {
  render(props) {
    const debouncedLoader = debounced(300, loadResults);
    return h("input", {
      autofocus: true,
      placeHolder: "NEW! Jump to docs...",
      className: "pa2 w-100 br1 border-box b--blue ba input-reset",
      onFocus: e => props.focus(),
      onBlur: e => setTimeout(_ => props.unfocus(), 200),
      onKeyDown: e => props.onKeyDown(e),
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
    "/d/" + result.group_name + "/" + result.jar_name + "/" + result.version
  );
}

const SingleResultView = (r, idx, isSelected, onMouseOver) => {
  const project =
    r.group_name === r.jar_name
      ? r.group_name
      : r.group_name + "/" + r.jar_name;
  const docsUri = resultUri(r);
  return h("a", { className: "no-underline black", href: docsUri }, [
    h(
      "div",
      {
        className: isSelected
          ? "pa3 bb b--light-gray bg-light-blue"
          : "pa3 bb b--light-gray",
        onMouseOver: () => {
          onMouseOver(idx);
        }
      },
      [
        h("h4", { className: "dib ma0" }, [
          project,
          h("span", { className: "ml2 gray normal" }, r.version)
        ]),
        h(
          "a",
          {
            className: "link blue ml2",
            href: docsUri
          },
          "view docs"
        )
        // h('span', {}, r.created)
      ]
    )
  ]);
};

const ResultsView = props => {
  return h(
    "div",
    {
      id: "results-view",
      className:
        "bg-white br1 br--bottom bb bl br b--blue absolute w-100 overflow-y-scroll",
      style: {
        top: "2.3rem",
        maxHeight: "20rem",
        boxShadow: "0 4px 10px rgba(0,0,0,0.1)"
      }
    },
    props.results
      .sort((a, b) => b.created - a.created)
      .map((r, idx) =>
        SingleResultView(r, idx, props.selectedIndex == idx, props.onMouseOver)
      )
  );
};

function restrictToViewport(container, selectedIndex) {
  let containerRect = container.getBoundingClientRect();
  let selectedRect = container.children[selectedIndex].getBoundingClientRect();
  let deltaTop = selectedRect.top - containerRect.top;
  let deltaBottom = selectedRect.bottom - containerRect.bottom;
  if (deltaTop < 0) {
    container.scrollBy(0, deltaTop);
  } else if (deltaBottom > 0) {
    container.scrollBy(0, deltaBottom);
  }
}

class App extends Component {
  handleInputKeyDown(e) {
    if (e.which === 13 && this.state.focused) {
      let result = this.state.results[this.state.selectedIndex];
      window.open(resultUri(result), "_self");
    } else if (e.which === 27) {
      this.setState({ focused: false });
    } else if (e.which === 38) {
      // arrow up
      e.preventDefault(); // prevents caret from moving in input field
      this.setState({
        selectedIndex: Math.max(this.state.selectedIndex - 1, 0)
      });
      restrictToViewport(
        this.base.querySelector("#results-view"),
        this.state.selectedIndex
      );
    } else if (e.which === 40) {
      // arrow down
      e.preventDefault();
      this.setState({
        selectedIndex: Math.min(
          this.state.selectedIndex + 1,
          this.state.results.length - 1
        )
      });
      restrictToViewport(
        this.base.querySelector("#results-view"),
        this.state.selectedIndex
      );
    }
  }

  constructor(props) {
    super(props);
    this.handleInputKeyDown = this.handleInputKeyDown.bind(this);
    this.state = { results: [], focused: false, selectedIndex: 0 };
  }

  render(props, state) {
    return h("div", { className: "relative system-sans-serif" }, [
      h(SearchInput, {
        newResultsCallback: rs => this.setState({ focused: true, results: rs }),
        onKeyDown: this.handleInputKeyDown,
        focus: () => this.setState({ focused: true }),
        unfocus: () => this.setState({ focused: false })
      }),
      state.focused && state.results.length > 0
        ? h(ResultsView, {
            results: state.results,
            selectedIndex: state.selectedIndex,
            onMouseOver: idx => this.setState({ selectedIndex: idx })
          })
        : null
    ]);
  }
}

export { App };
