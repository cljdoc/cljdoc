import { h, render } from "preact";
import { trackProjectOpened, Switcher } from "./switcher";
import { App } from "./search";
import { MobileNav } from "./mobile";
import { Navigator } from "./navigator";
import {
  isNSPage,
  isNSOfflinePage,
  isProjectDocumentationPage,
  initSrollIndicator,
  initToggleRaw,
  restoreSidebarScrollPos,
  toggleMetaDialog,
  addPrevNextPageKeyHandlers,
  saveSidebarScrollPos
} from "./cljdoc";
import { initRecentDocLinks } from "./recent-doc-links";

export type SidebarScrollPos = { page: string; scrollTop: number };

// Tracks recently opened projects in localStorage.
trackProjectOpened();

// Sidebar scroll position is saved when navigating away from the site.
// Here we restore that position when the site loads.
restoreSidebarScrollPos();

// Enable the switcher, which lets you rapidly switch between recently opened
// projects. The switcher is not included in offline docs.
const switcher = document.querySelector("#cljdoc-switcher");
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
const navigatorNode = document.querySelector("#js--cljdoc-navigator");
navigatorNode && render(<Navigator />, navigatorNode);

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
  const mobileNav = document.querySelector("#js--mobile-nav");
  mobileNav && render(<MobileNav />, mobileNav);
  toggleMetaDialog();
  addPrevNextPageKeyHandlers();
}

// Links to recent docsets on the homepage.
const docLinks = document.querySelector("#doc-links");
if (docLinks) {
  initRecentDocLinks(docLinks);
}

// Save the sidebar scroll position when navigating away from the site so we can restore it later.
window.onbeforeunload = saveSidebarScrollPos;
