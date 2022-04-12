import { h, render } from "preact";
import { useEffect, useState } from "preact/hooks";
import { debounced } from "./search";
import { DBSchema, IDBPDatabase, openDB } from "idb";
import cx from "classnames";
import lunr from "lunr";
import { flatMap, isUndefined, over, partition, sortBy } from "lodash";
import searchIcon from "../resources/public/search-icon.svg";

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
  indexItems: IndexItem[];
};

interface SearchsetsDB extends DBSchema {
  searchsets: {
    key: string;
    value: StoredSearchset;
  };
}

type StartLengthRange = [start: number, length: number];
type StartEndRange = [start: number, end: number];

type LunrSearchMetadataValue = {
  name?: { position: StartLengthRange[] };
  doc?: { position: StartLengthRange[] };
};

type LunrSearchMetadata = {
  [term: string]: LunrSearchMetadataValue;
};

type SearchResult = {
  indexItem: IndexItem;
  lunrResult: lunr.Index.Result;
  highlightedName: () => (string | h.JSX.Element)[];
  highlightedDoc: () => (string | h.JSX.Element)[] | undefined;
};

const clamp = (value: number, min: number, max: number) =>
  Math.min(Math.max(value, min), max);

const minMax = over([Math.min, Math.max]);

const isOverlap = (
  [aStart, aEnd]: StartEndRange,
  [bStart, bEnd]: StartEndRange
) => {
  return aStart <= bEnd && aEnd >= bStart;
};

const processPositions = (startLengthRanges: StartLengthRange[]) => {
  let remainingStartEndRanges = startLengthRanges.map(
    m => [m[0], m[0] + m[1]] as StartEndRange
  );

  const ranges: StartEndRange[] = [];

  while (remainingStartEndRanges.length > 0) {
    const [overlapping, nonOverlapping] = partition(
      remainingStartEndRanges,
      r => isOverlap(remainingStartEndRanges[0], r)
    );

    ranges.push(minMax(...overlapping.flat()) as StartEndRange);

    remainingStartEndRanges = nonOverlapping;
  }

  return sortBy(ranges, r => r[0]);
};

const mark = (ranges: StartEndRange[], text: string) => {
  let cursor = 0;

  const chunks = flatMap(ranges, range => {
    const before = text.slice(cursor, range[0]);
    const mark = <mark>{text.slice(range[0], range[1])}</mark>;
    cursor = range[1];

    return before.length > 0 ? [before, mark] : [mark];
  });

  const after = text.slice(cursor, text.length);

  if (after.length > 0) {
    chunks.push(after);
  }

  return chunks;
};

const trimMark = (range: StartEndRange, text: string) => {
  const marked = mark([range], text);

  const textPaddingCharacterCount = 60;

  const prefixLength = Math.min(range[0], textPaddingCharacterCount / 2);
  const postfixLength = textPaddingCharacterCount - prefixLength;

  const lastElement = marked[marked.length - 1];
  const hasPostEllipsis =
    typeof lastElement === "string" && lastElement.length > 50;

  const hasPreEllipsis = typeof marked[0] === "string" && marked[0].length > 50;

  if (typeof marked[0] === "string") {
    marked[0] = marked[0].slice(-prefixLength);
  }

  if (typeof lastElement === "string") {
    marked[marked.length - 1] = lastElement.slice(0, postfixLength - 1);
  }

  const truncatedMarkedText = [];

  hasPreEllipsis && truncatedMarkedText.push("... ");

  truncatedMarkedText.push(...marked);

  hasPostEllipsis && truncatedMarkedText.push(" ...");

  return truncatedMarkedText;
};

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

const fetchIndexItems = async (url: string, db: IDBPDatabase<SearchsetsDB>) => {
  const storedSearchset = await db.get("searchsets", url);
  if (
    storedSearchset &&
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
      indexItems: items
    },
    url
  );

  return items;
};

const buildSearchIndex = (indexItems: IndexItem[]) => {
  const searchIndex = lunr(function () {
    this.ref("id");
    this.field("name", { boost: 100 });
    this.field("doc");
    this.metadataWhitelist = ["position"];
    indexItems.forEach(indexItem => this.add(indexItem));
  });

  return searchIndex;
};

const ResultListItem = (props: {
  result: SearchResult;
  index: number;
  selected: boolean;
  hideResults: () => void;
}) => {
  const { result, selected, hideResults } = props;

  switch (result.indexItem.kind) {
    case "namespace":
    case "def":
    case "doc":
      const highlightedDoc = result.highlightedDoc();
      return (
        <li
          className={cx("pa2 bb b--light-gray", {
            "bg-light-blue": selected
          })}
        >
          <a
            className="no-underline black"
            href={result.indexItem.path}
            onClick={hideResults}
          >
            <div>{result.highlightedName()}</div>
            {highlightedDoc && <div>{result.highlightedDoc()}</div>}
          </a>
        </li>
      );

    default:
      // This should never happen but... just in case.
      // @ts-ignore
      throw new Error(`Unknown result type: ${result.kind}`);
  }
};

const search = (
  searchIndex: lunr.Index | undefined,
  indexItems: IndexItem[] | undefined,
  query: string
) => {
  if (!searchIndex || !indexItems) {
    return undefined;
  }

  const tokenizedQuery = [...query.split(/\s+/).filter(v => v.length > 0)];

  const lunrResults = searchIndex.query(lunrQuery => {
    lunrQuery.term(query, {
      fields: ["name"],
      boost: 100,
      wildcard: lunr.Query.wildcard.LEADING
    });
    lunrQuery.term(tokenizedQuery, {
      fields: ["doc"],
      wildcard: lunr.Query.wildcard.LEADING | lunr.Query.wildcard.TRAILING
    });
  });

  const results: SearchResult[] = lunrResults.map(lunrResult => {
    const indexItem = indexItems[Number(lunrResult.ref) - 1];
    const metadata = lunrResult.matchData.metadata as LunrSearchMetadata;

    const docRanges = processPositions(
      flatMap(metadata, m => m.doc?.position ?? [])
    );

    const nameRanges = processPositions(
      flatMap(metadata, m => m.name?.position ?? [])
    );

    const result = {
      indexItem,
      lunrResult: lunrResult,
      highlightedName: () => mark(nameRanges, indexItem.name),
      highlightedDoc: () => {
        return isUndefined(indexItem.doc)
          ? undefined
          : trimMark(docRanges[0], indexItem.doc);
      }
    };

    return result;
  });

  return results;
};

const debouncedSearch = debounced(300, search);

const SingleDocsetSearch = (props: { url: string }) => {
  const { url } = props;
  const [db, setDb] = useState<IDBPDatabase<SearchsetsDB> | undefined>();
  const [indexItems, setIndexItems] = useState<IndexItem[] | undefined>();
  const [searchIndex, setSearchIndex] = useState<lunr.Index | undefined>();

  const [results, setResults] = useState<SearchResult[]>([]);
  const [showResults, setShowResults] = useState<boolean>(false);
  const [selectedIndex, setSelectedIndex] = useState<number | undefined>();
  const currentURL = new URL(window.location.href);
  const [inputValue, setInputValue] = useState<string>(
    currentURL.searchParams.get("q") || ""
  );

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
            src={searchIcon}
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
            onFocus={(event: FocusEvent) => {
              const input = event.target as HTMLInputElement;
              input.classList.toggle("b--blue");
            }}
            onBlur={(event: FocusEvent) => {
              const input = event.target as HTMLInputElement;
              input.classList.toggle("b--blue");
            }}
            onKeyDown={(event: KeyboardEvent) => {
              const input = event.target as HTMLInputElement;

              if (event.key === "Escape") {
                setShowResults(false);
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
                  if (!isUndefined(selectedIndex) && results.length > 0) {
                    const redirectTo = new URL(
                      window.location.origin +
                        results[selectedIndex].indexItem.path
                    );

                    if (currentURL.toString() != redirectTo.toString()) {
                      console.log("assigning!", {
                        pathname: currentURL,
                        redirectTo
                      });

                      const params = redirectTo.searchParams;
                      params.set("q", input.value);
                      redirectTo.search = params.toString();
                      window.location.assign(redirectTo.toString());
                    }
                    setShowResults(false);
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

              if (input.value.length < 3) {
                setResults([]);
              } else {
                debouncedSearch(searchIndex, indexItems, input.value)
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
              result={result}
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
