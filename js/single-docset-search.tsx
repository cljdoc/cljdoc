import { h, render } from "preact";
import { useEffect, useRef, useState } from "preact/hooks";
import { debounced } from "./search";
import { DBSchema, IDBPDatabase, openDB } from "idb";
import cx from "classnames";

import elasticlunr from "elasticlunr";

elasticlunr.tokenizer.setSeperator(/[\s-.>=+\/]+/);

const SEARCHSET_VERSION = 1;

type Namespace = {
  name: string;
  path: string;
  platform: string;
  doc?: string;
};

type NamespaceWithKindAndId = Namespace & { kind: "namespace"; id: number };

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

type DefWithKindAndId = Def & { kind: "def"; id: number };

type Doc = {
  name: string;
  path: string;
  doc: string;
};
type DocWithKindAndId = Doc & { kind: "doc"; id: number };

type Searchset = {
  namespaces: Namespace[];
  defs: Def[];
  docs: Doc[];
};

type IndexItem = NamespaceWithKindAndId | DefWithKindAndId | DocWithKindAndId;

type StoredSearchset = {
  storedAt: string;
  version: number;
  indexItems: IndexItem[];
};

interface SearchsetsDB extends DBSchema {
  searchsets: {
    key: string;
    value: StoredSearchset;
  };
}

type Index = elasticlunr.Index<IndexItem>;
type SearchResult = {
  result: elasticlunr.SearchResults;
  doc: IndexItem;
};

const clamp = (value: number, min: number, max: number) =>
  Math.min(Math.max(value, min), max);

const mountSingleDocsetSearch = async () => {
  const singleDocsetSearchNode: HTMLElement | null = document.querySelector(
    "#js--single-docset-search"
  );

  const url = singleDocsetSearchNode?.dataset?.searchsetUrl;

  if (singleDocsetSearchNode && typeof url === "string") {
    render(<SingleDocsetSearch url={url} />, singleDocsetSearchNode);
  }
};

const isExpired = (dateString: string) => {
  const date = new Date(dateString);
  const expiresAt = new Date();
  // gross, mutation
  expiresAt.setDate(date.getDate() + 1);

  const now = new Date();

  // now > expiresAt === true when the current timestamp is after the expiration
  return now > expiresAt;
};

const evictBadSearchsets = async (db: IDBPDatabase<SearchsetsDB>) => {
  const keys = await db.getAllKeys("searchsets");

  for (const key in keys) {
    const storedSearchset = await db.get("searchsets", key);
    if (
      storedSearchset &&
      (storedSearchset.version !== SEARCHSET_VERSION ||
        isExpired(storedSearchset.storedAt) ||
        storedSearchset.indexItems.length === 0)
    ) {
      await db.delete("searchsets", key);
    }
  }

  return db;
};

const fetchIndexItems = async (url: string, db: IDBPDatabase<SearchsetsDB>) => {
  const storedSearchset = await db.get("searchsets", url);
  if (
    storedSearchset &&
    storedSearchset.version === SEARCHSET_VERSION &&
    !isExpired(storedSearchset.storedAt) &&
    storedSearchset.indexItems.length > 0
  ) {
    return storedSearchset.indexItems;
  }

  const items: IndexItem[] = [];

  const response = await fetch(url);
  const searchset: Searchset = await response.json();

  let id = 0;

  searchset.namespaces.forEach(ns => {
    id += 1;
    items.push({
      ...ns,
      kind: "namespace",
      id
    });
  });

  searchset.defs.forEach(def => {
    id += 1;
    items.push({
      ...def,
      kind: "def",
      id
    });
  });

  searchset.docs.forEach(doc => {
    id += 1;
    items.push({
      ...doc,
      kind: "doc",
      id
    });
  });

  await db.put(
    "searchsets",
    {
      storedAt: new Date().toISOString(),
      version: SEARCHSET_VERSION,
      indexItems: items
    },
    url
  );

  return items;
};

const buildSearchIndex = (indexItems: IndexItem[]): Index => {
  const searchIndex = elasticlunr<IndexItem>(index => {
    index.setRef("id");
    index.addField("name");
    index.addField("doc");
    index.saveDocument(true);
  });

  indexItems.forEach(indexItem => searchIndex.addDoc(indexItem));

  return searchIndex;
};

const ResultIcon = (props: { item: IndexItem }) => {
  const { item } = props;
  let label: string = item.kind;
  let text: string;

  const colors = {
    NS: "bg-light-purple",
    DOC: "bg-green",
    VAR: "bg-dark-blue",
    MAC: "bg-dark-red",
    PRO: "bg-light-red"
  };
  const defaultColor = "bg-black-70";

  switch (item.kind) {
    case "namespace":
      text = "NS";
      break;
    case "def":
      label = item.type;
      text = item.type.slice(0, 3).toUpperCase();
      break;
    case "doc":
      text = "DOC";
      break;
    default:
      throw new Error(`Unknown item kind: ${item}`);
  }

  // @ts-ignore
  const color = colors[text] || defaultColor;

  return (
    <div
      className={`pa1 white-90 br1 mr2 tc f6 ${color}`}
      style={{ width: "2.5rem", fontSize: "13px", marginBottom: "2px" }}
      aria-label={label}
      title={label}
    >
      {text}
    </div>
  );
};

const ResultName = (props: { item: IndexItem }) => {
  const { item } = props;

  if (item.kind === "def") {
    return (
      <div className="mb1">
        <span className="">{item.namespace}</span>/{item.name}
      </div>
    );
  }

  return <div className="mb1">{item.name}</div>;
};

const ResultListItem = (props: {
  searchResult: SearchResult;
  index: number;
  selected: boolean;
  hideResults: () => void;
}) => {
  const { searchResult, selected, hideResults } = props;
  const item = useRef<HTMLLIElement>(null);

  useEffect(() => {
    if (item.current && selected) {
      item.current.scrollIntoView({ block: "nearest" });
    }
  }, [item.current, selected]);

  const result = searchResult.doc;

  return (
    <li
      className={cx("pa2 bb b--light-gray", {
        "bg-light-blue": selected
      })}
      ref={item}
    >
      <a
        className="no-underline black"
        href={result.path}
        onClick={hideResults}
      >
        <div className="flex flex-row items-end">
          <ResultIcon item={result} />
          <div className="flex flex-column">
            <ResultName item={result} />
          </div>
        </div>
      </a>
    </li>
  );
};

const search = (
  searchIndex: Index | undefined,
  query: string
): SearchResult[] | undefined => {
  const results =
    searchIndex &&
    searchIndex.search(query, {
      bool: "OR",
      expand: true,
      fields: {
        name: { boost: 5 },
        doc: { boost: 2 }
      }
    });

  const resultsWithDocs = results?.map(r => ({
    result: r,
    doc: searchIndex?.documentStore.getDoc(r.ref)!
  }));

  // filter out duplicates
  return resultsWithDocs?.filter((outer, index) => {
    const outerDoc = outer.doc;

    switch (outerDoc.kind) {
      case "namespace":
        return (
          index ===
          resultsWithDocs.findIndex(
            inner =>
              inner.doc.kind === "namespace" &&
              inner.doc.name == outerDoc.name &&
              inner.doc.path === outerDoc.path
          )
        );
      case "def":
        return (
          index ===
          resultsWithDocs.findIndex(
            inner =>
              inner.doc.kind === "def" &&
              inner.doc.namespace == outerDoc.namespace &&
              inner.doc.name == outerDoc.name &&
              inner.doc.path === outerDoc.path
          )
        );
      default:
        return true;
    }
  });
};

const debouncedSearch = debounced(300, search);

const SingleDocsetSearch = (props: { url: string }) => {
  const { url } = props;
  const [db, setDb] = useState<IDBPDatabase<SearchsetsDB> | undefined>();
  const [indexItems, setIndexItems] = useState<IndexItem[] | undefined>();
  const [searchIndex, setSearchIndex] = useState<Index | undefined>();

  const [results, setResults] = useState<SearchResult[]>([]);
  const [showResults, setShowResults] = useState<boolean>(false);
  const [selectedIndex, setSelectedIndex] = useState<number | undefined>();
  const currentURL = new URL(window.location.href);
  const [inputValue, setInputValue] = useState<string>(
    currentURL.searchParams.get("q") || ""
  );

  const inputElement = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (
        inputElement.current &&
        (event.metaKey || event.ctrlKey) &&
        event.key === "/"
      ) {
        inputElement.current.focus();
      }
    };

    document.addEventListener("keydown", handleKeyDown);

    return () => {
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [inputElement]);

  useEffect(() => {
    openDB<SearchsetsDB>("cljdoc-searchsets-store", 1, {
      upgrade(db) {
        db.createObjectStore("searchsets");
      }
    })
      .then(evictBadSearchsets)
      .then(setDb)
      .catch(console.error);
  }, []);

  useEffect(() => {
    db && fetchIndexItems(url, db).then(setIndexItems).catch(console.error);
  }, [url, db]);

  useEffect(() => {
    if (indexItems) {
      setSearchIndex(buildSearchIndex(indexItems));
    }
  }, [indexItems]);

  const onArrowUp = () => {
    if (results.length > 0) {
      const max = results.length - 1;

      if (typeof selectedIndex === "undefined" || selectedIndex === 0) {
        setSelectedIndex(max);
      } else {
        setSelectedIndex(selectedIndex - 1);
      }
    } else {
      setSelectedIndex(undefined);
    }
  };

  const onArrowDown = () => {
    if (results.length > 0) {
      const max = results.length - 1;

      if (typeof selectedIndex === "undefined" || selectedIndex === max) {
        setSelectedIndex(0);
      } else {
        setSelectedIndex(selectedIndex + 1);
      }
    } else {
      setSelectedIndex(undefined);
    }
  };

  const clampSelectedIndex = () => {
    if (typeof selectedIndex === "undefined") {
      return;
    }

    if (results.length === 0 && typeof selectedIndex !== "undefined") {
      setSelectedIndex(undefined);
      return;
    }

    const index = clamp(selectedIndex, 0, results.length - 1);

    if (index !== selectedIndex) {
      setSelectedIndex(index);
    }
  };

  clampSelectedIndex();

  return (
    <div>
      <form className="black-80 w-100" onSubmit={e => e.preventDefault()}>
        <div style={{ position: "relative" }}>
          <img
            src="/search-icon.svg"
            className="w1 h1"
            style={{
              position: "absolute",
              left: "0.58rem",
              top: "0.58rem",
              zIndex: 1
            }}
          />
          <input
            name="single-docset-search-term"
            type="text"
            aria-describedby="single-docset-search-term-description"
            className="input-reset ba b--black-20 pa2 pl4 db br1"
            value={inputValue}
            disabled={!searchIndex}
            placeholder={searchIndex ? "Search..." : "Loading..."}
            ref={inputElement}
            onFocus={(event: FocusEvent) => {
              const input = event.target as HTMLInputElement;
              input.classList.toggle("b--blue");

              debouncedSearch(searchIndex, input.value)
                .then(results => {
                  if (results) {
                    setResults(results);
                  } else {
                    setResults([]);
                  }

                  if (!showResults) {
                    setShowResults(true);
                  }
                })
                .catch(console.error);
            }}
            onBlur={(event: FocusEvent) => {
              const input = event.target as HTMLInputElement;
              input.classList.toggle("b--blue");
            }}
            onKeyDown={(event: KeyboardEvent) => {
              const input = event.target as HTMLInputElement;

              if (event.key === "Escape") {
                if (showResults) {
                  setShowResults(false);
                } else {
                  input.blur();
                }
              } else if (event.key === "ArrowUp") {
                event.preventDefault(); // prevents caret from moving in input field
                !showResults && setShowResults(true);
                onArrowUp();
              } else if (event.key === "ArrowDown") {
                event.preventDefault(); // prevents caret from moving in input field
                !showResults && setShowResults(true);
                onArrowDown();
              } else if (event.key === "Enter") {
                event.preventDefault();

                if (showResults) {
                  if (
                    typeof selectedIndex !== "undefined" &&
                    results.length > 0
                  ) {
                    const redirectTo = new URL(
                      window.location.origin + results[selectedIndex].doc.path
                    );

                    const params = redirectTo.searchParams;
                    params.set("q", input.value);
                    redirectTo.search = params.toString();

                    if (currentURL.href !== redirectTo.href) {
                      window.location.assign(redirectTo.toString());
                    }
                    setShowResults(false);
                    input.blur();
                  }
                } else {
                  setShowResults(true);
                }
              }
            }}
            onInput={event => {
              event.preventDefault();
              const input = event.target as HTMLInputElement;

              setInputValue(input.value);

              if (input.value.length === 0) {
                setResults([]);
              } else {
                debouncedSearch(searchIndex, input.value)
                  .then(results => {
                    if (results) {
                      setResults(results);
                    } else {
                      setResults([]);
                    }

                    if (!showResults) {
                      setShowResults(true);
                    }
                  })
                  .catch(console.error);
              }
            }}
          />
        </div>
      </form>
      {showResults && results.length > 0 && (
        <ol
          className="list pa0 ma0 no-underline black bg-white br--bottom ba br1 b--blue absolute overflow-y-scroll"
          style={{
            zIndex: 1,
            maxWidth: "90vw",
            maxHeight: "80vh"
          }}
        >
          {results.map((result, index) => (
            <ResultListItem
              searchResult={result}
              index={index}
              selected={selectedIndex === index}
              hideResults={() => showResults && setShowResults(false)}
            />
          ))}
        </ol>
      )}
    </div>
  );
};

export { SingleDocsetSearch, mountSingleDocsetSearch };
