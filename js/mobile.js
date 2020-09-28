import { Component, render, h } from "preact";
import * as doctree from "./doctree";

export class MobileNav extends Component {
  constructor() {
    super();
    this.toggleNav = this.toggleNav.bind(this);
  }

  toggleNav() {
    let mainScrollView = document.querySelector(".js--main-scroll-view");
    let mainSidebar = document.querySelector(".js--main-sidebar");
    let isNavShown = mainScrollView.classList.contains("dn");
    if (isNavShown) {
      let scrollPos = window.scrollY;
      mainScrollView.classList.remove("dn"); // show main scroll view / content area
      mainSidebar.classList.replace("db", "dn"); // hide sidebar
      window.scrollTo(0, this.state.mainViewScrollPos); // scroll after(!) swapping content
      this.setState({ showNav: false, navViewScrollPos: scrollPos });
    } else {
      let scrollPos = window.scrollY;
      mainScrollView.classList.add("dn"); // hide main scroll view / content area
      mainSidebar.classList.add("flex-grow-1"); // make sure nav fills width of screen
      mainSidebar.classList.replace("dn", "db"); // show sidebar
      window.scrollTo(0, this.state.navViewScrollPos); // scroll after(!) swapping content
      this.setState({ showNav: true, mainViewScrollPos: scrollPos });
    }
  }

  render(props, state) {
    let btnMsg = state.showNav
      ? "Back to Content"
      : "Tap for Articles & Namespaces";
    let btnIcon = state.showNav ? "chevronLeft" : "list";
    let btnSrc = "https://microicon-clone.vercel.app/" + btnIcon + "/32";
    return (
      <div class="bg-light-gray">
        <button
          class="outline-0 bw0 bg-transparent w-100 tl pa2"
          onClick={this.toggleNav}
        >
          <img class="dib mr2 v-mid" src={btnSrc} height="32" />
          <span class="dib">{btnMsg}</span>
        </button>
      </div>
    );
  }
}
