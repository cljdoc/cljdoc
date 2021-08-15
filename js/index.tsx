import { render } from "preact";
import { trackProjectOpened, Switcher } from "./switcher";
import { App } from "./search";
import { MobileNav } from "./mobile";
import { Navigator } from "./navigator";
import {
  isNSPage,
  isProjectDocumentationPage,
  initSrollIndicator,
  initToggleRaw,
  restoreSidebarScrollPos,
  toggleMetaDialog,
  addPrevNextPageKeyHandlers
} from "./cljdoc";

trackProjectOpened();
restoreSidebarScrollPos();

render(<Switcher />, document.querySelector("#cljdoc-switcher"));

const searchNode: HTMLElement = document.querySelector("#cljdoc-search");
if (searchNode && searchNode.dataset) {
  render(<App initialValue={searchNode.dataset.initialValue} />, searchNode);
}

const navigatorNode = document.querySelector("#js--cljdoc-navigator");
if (navigatorNode) {
  render(<Navigator />, navigatorNode);
}

if (isNSPage()) {
  initSrollIndicator();
  initToggleRaw();
}

if (isProjectDocumentationPage()) {
  render(<MobileNav />, document.querySelector("#js--mobile-nav"));
  toggleMetaDialog();
  addPrevNextPageKeyHandlers();
}

window.onbeforeunload = function () {
  var sidebar = Array.from(document.querySelectorAll(".js--main-sidebar"))[0];
  if (sidebar) {
    var scrollTop = sidebar.scrollTop;
    var page = window.location.pathname.split("/").slice(0, 5).join("/");
    var data = { page: page, scrollTop: scrollTop };
    console.log(data);
    localStorage.setItem("sidebarScrollPos", JSON.stringify(data));
  }
};
