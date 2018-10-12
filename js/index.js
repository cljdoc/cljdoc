import { render, h } from 'preact';
import { trackProjectOpened, Switcher } from './switcher';
import { App } from './search';
import { isNSPage, isDocPage, initSrollIndicator, initToggleRaw, initDocTitle, restoreSidebarScrollPos } from './cljdoc';

trackProjectOpened()
restoreSidebarScrollPos()
render(h(Switcher), document.querySelector('#cljdoc-switcher'))
render(h(App), document.querySelector('#cljdoc-search'))

if (isNSPage()) {
  initSrollIndicator()
  initToggleRaw()
}

if (isDocPage()) {
  initDocTitle()
}

window.onbeforeunload = function(){
  var sidebar = Array.from(document.querySelectorAll(".js--sidebar"))[0]
  if (sidebar) {
    var scrollTop = sidebar.scrollTop
    var page = window.location.pathname.split("/").slice(0,5).join("/")
    var data = {"page": page, "scrollTop": scrollTop}
    console.log(data)
    localStorage.setItem("sidebarScrollPos", JSON.stringify(data))
  }
};
