import { Component, FunctionComponent } from "preact";

// Various functions and components used to show lists of results

// Navigating this list is done via events from the respective
// input components and thus not part of this code. Instead it is
// expected that a selectedIndex is passed to indicate which result
// is the currently selected one.

function restrictToViewport(container: Element, selectedIndex: number) {
  const containerRect = container.getBoundingClientRect();
  const selectedRect =
    container.children[selectedIndex].getBoundingClientRect();
  const deltaTop = selectedRect.top - containerRect.top;
  const deltaBottom = selectedRect.bottom - containerRect.bottom;
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

export type ResultViewComponent<ResultType> = FunctionComponent<{
  result: ResultType;
  isSelected: boolean;
  selectResult: () => void;
}>;

type ResultsViewProps<ResultType> = {
  resultView: ResultViewComponent<ResultType>;
  results: ResultType[];
  selectedIndex: number;
  onMouseOver: (index: number) => void;
};

type ResultsViewState = Record<string, never>;

export class ResultsView<ResultType> extends Component<
  ResultsViewProps<ResultType>,
  ResultsViewState
> {
  resultsViewNode?: Element | null;

  componentDidUpdate(
    prevProps: ResultsViewProps<ResultType>,
    _state: ResultsViewState
  ) {
    if (
      this.props.selectedIndex !== prevProps.selectedIndex &&
      this.resultsViewNode
    ) {
      restrictToViewport(this.resultsViewNode, this.props.selectedIndex);
    }
  }

  render(props: ResultsViewProps<ResultType>, _state: ResultsViewState) {
    return (
      <div
        className="bg-white br1 br--bottom bb bl br b--blue w-100 overflow-y-scroll"
        style={{ maxHeight: "20rem" }}
        ref={node => (this.resultsViewNode = node)}
      >
        {props.results.map((r, idx) => (
          <props.resultView
            result={r}
            isSelected={props.selectedIndex === idx}
            selectResult={() => props.onMouseOver(idx)}
          />
        ))}
      </div>
    );
  }
}
