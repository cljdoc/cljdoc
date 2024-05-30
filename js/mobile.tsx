import { h, Component } from "preact";

type MobileNavProps = any;

type MobileNavState = {
  mainViewScrollPos: number;
  navViewScrollPos: number;
  showNav: boolean;
};

export class MobileNav extends Component<MobileNavProps, MobileNavState> {
  constructor() {
    super();
    this.toggleNav = this.toggleNav.bind(this);
  }

  toggleNav() {
    const mainScrollView = document.querySelector(".js--main-scroll-view");
    const mainSidebar = document.querySelector(".js--main-sidebar");
    const isNavShown = mainScrollView && mainScrollView.classList.contains("dn");
    if (isNavShown) {
      const scrollPos = window.scrollY;
      mainScrollView!.classList.remove("dn"); // show main scroll view / content area
      mainSidebar && mainSidebar.classList.replace("db", "dn"); // hide sidebar
      window.scrollTo(0, this.state.mainViewScrollPos); // scroll after(!) swapping content
      this.setState({ showNav: false, navViewScrollPos: scrollPos });
    } else {
      const scrollPos = window.scrollY;
      mainScrollView && mainScrollView.classList.add("dn"); // hide main scroll view / content area
      mainSidebar && mainSidebar.classList.add("flex-grow-1"); // make sure nav fills width of screen
      mainSidebar && mainSidebar.classList.replace("dn", "db"); // show sidebar
      window.scrollTo(0, this.state.navViewScrollPos); // scroll after(!) swapping content
      this.setState({ showNav: true, mainViewScrollPos: scrollPos });
    }
  }

  render(_props: MobileNavProps, state: MobileNavState) {
    const btnMsg = state.showNav
      ? "Back to Content"
      : "Tap for Articles & Namespaces";
    const btnIcon = state.showNav ? "chevronLeft" : "list";
    const btnSrc = "https://microicon-clone.vercel.app/" + btnIcon + "/32";
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
