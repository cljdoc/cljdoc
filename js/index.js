import { render, h } from "preact";
import { trackProjectOpened, Switcher } from "./switcher";
import { hideNestedArticles } from "./doctree";
import { App } from "./search";
import { MobileNav } from "./mobile";
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
hideNestedArticles();

render(h(Switcher), document.querySelector("#cljdoc-switcher"));

const searchNode = document.querySelector("#cljdoc-search");
if (searchNode) {
  render(
    h(App, { initialValue: searchNode.getAttribute("initial-value") }),
    searchNode
  );
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
