import { Component, h } from "preact";

// Various functions and components used to show lists of results

// Navigating this list is done via events from the respective
// input components and thus not part of this code. Instead it is
// expected that a selectedIndex is passed to indicate which result
// is the currently selected one.

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

// Props required by ResultsView
// - results: list of results to render
// - selectedIndex: index of currently selected result
// - onMouseOver: what should happen when hovering a single result
// - resultView: view for single result,

// `resultView` expects the following args (not props)
//   - result: result to be rendered
//   - isSelected: whether the result should be displayed as currently selected
//   - onMouseOver: a no-args function to call when hovering the result
export class ResultsView extends Component {
  componentDidUpdate(prevProps, _) {
    if (this.props.selectedIndex !== prevProps.selectedIndex) {
      restrictToViewport(this.resultsViewNode, this.props.selectedIndex);
    }
  }

  render(props, _) {
    return h(
      "div",
      {
        className:
          "bg-white br1 br--bottom bb bl br b--blue w-100 overflow-y-scroll",
        style: { maxHeight: "20rem" },
        ref: node => (this.resultsViewNode = node)
      },
      props.results
        .sort((a, b) => b.created - a.created)
        .map((r, idx) =>
          props.resultView(r, props.selectedIndex === idx, () =>
            props.onMouseOver(idx)
          )
        )
    );
  }
}
