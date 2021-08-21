import { h, Component, FunctionComponent } from "preact";
import { CljdocProject } from "./switcher";

// Various functions and components used to show lists of results

// Navigating this list is done via events from the respective
// input components and thus not part of this code. Instead it is
// expected that a selectedIndex is passed to indicate which result
// is the currently selected one.

function restrictToViewport(container: Element, selectedIndex: number) {
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

export type ResultViewComponent = FunctionComponent<{
  result: CljdocProject;
  isSelected: boolean;
  selectResult: () => any;
}>;

type ResultsViewProps = {
  resultView: ResultViewComponent;
  results: CljdocProject[];
  selectedIndex: number;
  onMouseOver: (index: number) => any;
};

type ResultsViewState = any;

export class ResultsView extends Component<ResultsViewProps, ResultsViewState> {
  resultsViewNode?: Element | null;

  componentDidUpdate(prevProps: ResultsViewProps, _state: ResultsViewState) {
    if (
      this.props.selectedIndex !== prevProps.selectedIndex &&
      this.resultsViewNode
    ) {
      restrictToViewport(this.resultsViewNode, this.props.selectedIndex);
    }
  }

  render(props: ResultsViewProps, _state: any) {
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
