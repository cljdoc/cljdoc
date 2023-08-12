import { h, render } from "preact";
import { trackProjectOpened, Switcher } from "./switcher";
import { App } from "./search";
import { MobileNav } from "./mobile";
import { Navigator } from "./navigator";
import {
  isNSPage,
  isNSOverviewPage,
  isNSOfflinePage,
  isProjectDocumentationPage,
  initSrollIndicator,
  initToggleRaw,
  restoreSidebarScrollPos,
  toggleMetaDialog,
  toggleArticlesTip,
  addPrevNextPageKeyHandlers,
  saveSidebarScrollPos
} from "./cljdoc";
import { initRecentDocLinks } from "./recent-doc-links";
import { mountSingleDocsetSearch } from "./single-docset-search";
import { mergeHTMLPlugin } from "./hljs-merge-plugin";
import { copyButtonPlugin } from "./hljs-copybutton-plugin";

export type SidebarScrollPos = { page: string; scrollTop: number };

// Tracks recently opened projects in localStorage.
trackProjectOpened();

// Sidebar scroll position is saved when navigating away from the site.
// Here we restore that position when the site loads.
restoreSidebarScrollPos();

// Enable the switcher, which lets you rapidly switch between recently opened
// projects. The switcher is not included in offline docs.
const switcher = document.querySelector("[data-id='cljdoc-switcher']");
switcher && render(<Switcher />, switcher);

// Libraries search, found on cljdoc homepage and the 404 page.
const searchNode: HTMLElement | null = document.querySelector("#cljdoc-search");
if (searchNode && searchNode.dataset) {
  render(
    <App
      initialValue={searchNode.dataset.initialValue}
      results={[]}
      focused={false}
      selectedIndex={0}
    />,
    searchNode
  );
}

// Used for navigating on the /versions page.
const navigatorNode = document.querySelector("[data-id='cljdoc-js--cljdoc-navigator']");
navigatorNode && render(<Navigator />, navigatorNode);

if (isNSOverviewPage()) {
  initToggleRaw();
}

// Namespace page for online docs.
if (isNSPage()) {
  initSrollIndicator();
  initToggleRaw();
}

// Namespace page for offline docs.
if (isNSOfflinePage()) {
  initToggleRaw();
}

// For just general documentation pages, specifically routes that begin with /d/.
if (isProjectDocumentationPage()) {
  // Special handling for mobile nav. It makes the sidebar from desktop toggleable.
  const mobileNav = document.querySelector("[data-id='cljdoc-js--mobile-nav']");
  mobileNav && render(<MobileNav />, mobileNav);
  toggleMetaDialog();
  toggleArticlesTip();
  addPrevNextPageKeyHandlers();
}

// Links to recent docsets on the homepage.
const docLinks = document.querySelector("[data-id='cljdoc-doc-links']");
if (docLinks) {
  initRecentDocLinks(docLinks);
}

// Mount the single docset search if the search element is present.
mountSingleDocsetSearch();

// Save the sidebar scroll position when navigating away from the site so we can restore it later.
window.onbeforeunload = saveSidebarScrollPos;

// make the hljs plugins available to our layout page
window.mergeHTMLPlugin = mergeHTMLPlugin;
window.copyButtonPlugin = copyButtonPlugin;
