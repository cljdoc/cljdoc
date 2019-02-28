import { render, h } from "preact";
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
  toggleMetaDialog
} from "./cljdoc";

trackProjectOpened();
restoreSidebarScrollPos();

render(h(Switcher), document.querySelector("#cljdoc-switcher"));

const searchNode = document.querySelector("#cljdoc-search");
if (searchNode) {
  render(h(App, { initialValue: searchNode.dataset.initialValue }), searchNode);
}

const navigatorNode = document.querySelector("#js--cljdoc-navigator");
if (navigatorNode) {
  render(h(Navigator), navigatorNode);
}

if (isNSPage()) {
  initSrollIndicator();
  initToggleRaw();
}

if (isProjectDocumentationPage()) {
  render(h(MobileNav), document.querySelector("#js--mobile-nav"));
  toggleMetaDialog();
}

window.onbeforeunload = function() {
  var sidebar = Array.from(document.querySelectorAll(".js--main-sidebar"))[0];
  if (sidebar) {
    var scrollTop = sidebar.scrollTop;
    var page = window.location.pathname
      .split("/")
      .slice(0, 5)
      .join("/");
    var data = { page: page, scrollTop: scrollTop };
    console.log(data);
    localStorage.setItem("sidebarScrollPos", JSON.stringify(data));
  }
};
