import { render, h } from "preact";
import { trackProjectOpened, Switcher } from "./switcher";
import { hideNestedArticles } from "./doctree";
import { App } from "./search";
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
render(h(App), document.querySelector("#cljdoc-search"));

if (isNSPage()) {
  initSrollIndicator();
  initToggleRaw();
}

if (isProjectDocumentationPage()) {
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
