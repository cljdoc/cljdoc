import { h, render } from "preact";
import { useMemo } from "preact/hooks";

type Namespace = {
  name: string;
  path: string;
  platform: string;
  doc?: string;
};

type Def = {
  namespace: string;
  name: string;
  type: string;
  path: string;
  platform: string;
  doc?: string;
  arglists?: any[][];
  members: {
    type: string;
    name: string;
    arglists: any[][];
    doc?: string;
  };
};

type Doc = {
  name: string;
  path: string;
  doc: string;
};

type Searchset = {
  namespaces: Namespace[];
  defs: Def[];
  docs: Doc[];
};

const mountSingleDocsetSearch = () => {
  const singleDocsetSearchNode: HTMLElement | null = document.querySelector(
    "#js--single-docset-search"
  );

  if (typeof singleDocsetSearchNode?.dataset?.searchsetUrl === "string") {
    fetch(singleDocsetSearchNode.dataset.searchsetUrl)
      .then(response => response.json())
      .then(data =>
        render(
          <SingleDocsetSearch searchset={data as Searchset} />,
          singleDocsetSearchNode
        )
      )
      .catch(err => console.error(err));
  }
};

const SingleDocsetSearch = (props: { searchset: Searchset }) => {
  const searchset = useMemo(() => {
    props.searchset;
  }, [props.searchset]);

  return (
    <form className="black-80">
      <div className="measure">
        <label className="f6 b db mb2" for="single-docset-search-term">
          Search
        </label>
        <input
          name="single-docset-search-term"
          type="text"
          aria-describedby="single-docset-search-term-description"
          className="input-reset ba b--black-20 pa2 mb2 db w-100"
        ></input>
      </div>
      <small
        id="single-docset-search-term-description"
        className="f6 black-60 db mb2"
      >
        Search documents, namespaces, vars, macros, protocols, and more.
      </small>
    </form>
  );
};

export { SingleDocsetSearch, mountSingleDocsetSearch };

// [:form.black-80
//    [:div.measure
//     [:label.f6.b.db.mb2
//      {:for "cljdoc-searchset-search"}
//      "Search"]
//     [:input#cljdoc-searchset-search-input.input-reset.ba.b--black-20.pa2.mb2.db.w-100
//      {:name "cljdoc-searchset-search"
//       :type "text"
//       :aria-describedby "cljdoc-searchset-search-desc"
//       :data-searchset-index-url (routes/url-for :api/searchset
//                                                 :params
//                                                 version-entity)}]
//     [:small#cljdoc-searchset-search-desc.f6.black-60.db.mb2
//      "Search documents, namespaces, vars, macros, protocols, and more."]]]
