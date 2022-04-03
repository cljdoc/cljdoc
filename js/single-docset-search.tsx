import { h, render, Fragment } from "preact";
import { useEffect, useState } from "preact/hooks";
import { Document } from "flexsearch";
import { debounced } from "./search";
import { DBSchema, IDBPDatabase, openDB } from "idb";

type Namespace = {
  name: string;
  path: string;
  platform: string;
  doc?: string;
};

type NamespaceWithKind = Namespace & { kind: "namespace" };

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

type DefWithKind = Def & { kind: "def" };

type Doc = {
  name: string;
  path: string;
  doc: string;
};
type DocWithKind = Doc & { kind: "doc" };

type Searchset = {
  namespaces: Namespace[];
  defs: Def[];
  docs: Doc[];
};

type IndexItem = NamespaceWithKind | DefWithKind | DocWithKind;

interface SearchsetsDB extends DBSchema {
  searchsets: {
    key: string;
    value: IndexItem[];
  };
}

const mountSingleDocsetSearch = async () => {
  const singleDocsetSearchNode: HTMLElement | null = document.querySelector(
    "#js--single-docset-search"
  );

  const url = singleDocsetSearchNode?.dataset?.searchsetUrl;

  if (singleDocsetSearchNode && typeof url === "string") {
    render(<SingleDocsetSearch url={url} />, singleDocsetSearchNode);
  }
};

const fetchIndexItems = async (url: string, db: IDBPDatabase<SearchsetsDB>) => {
  let items = await db.get("searchsets", url);

  if (items) {
    return items;
  }

  const response = await fetch(url);
  const searchset: Searchset = await response.json();

  items = [
    ...searchset.namespaces.map<NamespaceWithKind>(ns => ({
      ...ns,
      kind: "namespace"
    })),
    ...searchset.defs.map<DefWithKind>(def => ({
      ...def,
      kind: "def"
    })),
    ...searchset.docs.map<DocWithKind>(doc => ({
      ...doc,
      kind: "doc"
    }))
  ];

  await db.put("searchsets", items, url);

  return items;
};

const buildSearchIndex = (indexItems: IndexItem[]) => {
  const searchIndex = new Document<IndexItem, true>({
    document: {
      id: "id",
      index: ["name", "doc"],
      store: true
    },
    tokenize: "forward",
    context: true,
    encode: "advanced"
  });

  indexItems.forEach((indexItem, i) => searchIndex.append(i, indexItem));

  return searchIndex;
};

const SingleDocsetSearch = (props: { url: string }) => {
  const { url } = props;
  const [db, setDb] = useState<IDBPDatabase<SearchsetsDB> | undefined>();
  const [indexItems, setIndexItems] = useState<IndexItem[] | undefined>();
  const [searchIndex, setSearchIndex] = useState<
    Document<IndexItem, true> | undefined
  >();
  const [results, setResults] = useState<IndexItem[]>([]);
  const [outline, setOutline] = useState<boolean>(false);

  useEffect(() => {
    openDB<SearchsetsDB>("cljdoc-searchsets-store", 1, {
      upgrade(db) {
        db.createObjectStore("searchsets");
      }
    })
      .then(setDb)
      .catch(console.error);
  }, []);

  useEffect(() => {
    db && fetchIndexItems(url, db).then(setIndexItems).catch(console.error);
  }, [url, db]);

  useEffect(() => {
    indexItems && setSearchIndex(buildSearchIndex(indexItems));
  }, [indexItems]);

  const debouncedSearch = debounced(
    100,
    (query: string) =>
      searchIndex &&
      searchIndex
        .search(query, undefined, { enrich: true })
        .flatMap(r => r.result.map(d => d.doc))
  );

  console.log({ results });

  return (
    <div
      className={
        outline || results.length > 0
          ? "ba b--solid b--black-40"
          : "ba b--dashed b--black-40"
      }
      style={{ margin: "-1rem", padding: "1rem" }}
    >
      <form className="black-80" onSubmit={e => e.preventDefault()}>
        <div className="measure">
          <label className="f6 b db mb2" for="single-docset-search-term">
            Search
          </label>
          <input
            name="single-docset-search-term"
            type="text"
            aria-describedby="single-docset-search-term-description"
            className="input-reset ba b--black-20 pa2 mb2 db w-100"
            disabled={!searchIndex}
            placeholder={searchIndex ? undefined : "Loading..."}
            onFocus={() => setOutline(true)}
            onBlur={() => setOutline(false)}
            onKeyDown={(event: KeyboardEvent) => {
              const input = event.target as HTMLInputElement;

              if (event.key === "Escape") {
                input.value = "";
                setResults([]);
              } else if (event.key === "ArrowUp") {
                event.preventDefault(); // prevents caret from moving in input field
                // onArrowUp();
              } else if (event.key === "ArrowDown") {
                event.preventDefault(); // prevents caret from moving in input field
                // onArrowDown();
              }
            }}
            onInput={event => {
              event.preventDefault();
              const input = event.target as HTMLInputElement;
              debouncedSearch(input.value)
                .then(results => results && setResults(results))
                .catch(console.error);
            }}
          />
        </div>
        <small
          id="single-docset-search-term-description"
          className="f6 black-60 db mb2"
        >
          Search documents, namespaces, vars, macros, protocols, and more.
        </small>
      </form>
      {results && (
        <ol className="list pl0">
          {results.map(r => (
            <li>
              <a href={r.path}>{r.name}</a>
            </li>
          ))}
        </ol>
      )}
    </div>
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
