import { SidebarScrollPos } from "./index";

function isNSPage(): boolean {
  return !!document.querySelector(".ns-page");
}

function isNSOfflinePage(): boolean {
  return !!document.querySelector("ns-offline-page");
}

function isProjectDocumentationPage(): boolean {
  let pathSegs = window.location.pathname.split("/");
  return pathSegs.length >= 5 && pathSegs[1] == "d";
}

function initSrollIndicator(): void {
  var mainScrollView = document.querySelector(".js--main-scroll-view");
  var sidebarScrollView = document.querySelector(
    ".js--namespace-contents-scroll-view"
  );
  var defBlocks = Array.from(document.querySelectorAll(".def-block"));
  var defItems = Array.from(document.querySelectorAll(".def-item"));

  function isElementVisible(container: Element, el: Element) {
    var { y: etop, height } = el.getBoundingClientRect(),
      ebottom = etop + height,
      cbottom = window.innerHeight,
      ctop = cbottom - container.clientHeight;
    return etop <= cbottom && ebottom >= ctop;
  }

  function drawScrollIndicator() {
    defBlocks.forEach((el: Element, idx) => {
      var defItem = defItems[idx];
      if (
        mainScrollView &&
        sidebarScrollView &&
        isElementVisible(mainScrollView, el)
      ) {
        defItem.classList.add("scroll-indicator");
        if (idx === 0) {
          sidebarScrollView.scrollTop = 1;
        } else if (isElementVisible(sidebarScrollView, defItem) === false) {
          defItem.scrollIntoView();
        }
      } else {
        defItem.classList.remove("scroll-indicator");
      }
    });
  }

  mainScrollView &&
    mainScrollView.addEventListener("scroll", drawScrollIndicator);

  drawScrollIndicator();
}

function initToggleRaw() {
  let toggles: HTMLElement[] = Array.from(
    document.querySelectorAll(".js--toggle-raw")
  );

  function addToggleHandlers() {
    toggles.forEach(el => {
      el.addEventListener("click", function () {
        let parent = el.parentElement;
        let markdowns = parent && parent.querySelectorAll(".markdown");
        let raws = parent && parent.querySelectorAll(".raw");
        markdowns &&
          markdowns.forEach((markdown, idx) => {
            let raw = raws && raws[idx];
            if (markdown.classList.contains("dn")) {
              markdown.classList.remove("dn");
              raw && raw.classList.add("dn");
              el.innerText = "raw docstring";
            } else {
              markdown.classList.add("dn");
              raw && raw.classList.remove("dn");
              el.innerText = "formatted docstring";
            }
          });
      });
    });
  }

  addToggleHandlers();
}

function restoreSidebarScrollPos() {
  var scrollPosData = JSON.parse(
    localStorage.getItem("sidebarScrollPos") || "null"
  );
  var page = window.location.pathname.split("/").slice(0, 5).join("/");

  if (scrollPosData && page == scrollPosData.page) {
    var mainSidebar = document.querySelector(".js--main-sidebar");
    if (mainSidebar) {
      mainSidebar.scrollTop = scrollPosData.scrollTop;
    }
  }

  localStorage.removeItem("sidebarScrollPos");
}

function saveSidebarScrollPos() {
  var sidebar = Array.from(document.querySelectorAll(".js--main-sidebar"))[0];
  if (sidebar) {
    var scrollTop = sidebar.scrollTop;
    var page = window.location.pathname.split("/").slice(0, 5).join("/");
    var data: SidebarScrollPos = { page: page, scrollTop: scrollTop };
    localStorage.setItem("sidebarScrollPos", JSON.stringify(data));
  }
}

function toggleMetaDialog() {
  if (document.querySelector(".js--main-scroll-view")) {
    const metaIcon = document.getElementById("js--meta-icon");
    const metaDialog = document.getElementById("js--meta-dialog");
    const metaClose = document.getElementById("js--meta-close");

    if (metaIcon) {
      metaIcon.onclick = () => {
        metaIcon.classList.replace("db-ns", "dn");
        metaDialog && metaDialog.classList.replace("dn", "db-ns");
      };
    }

    if (metaClose) {
      metaClose.onclick = () => {
        metaDialog && metaDialog.classList.replace("db-ns", "dn");
        metaIcon && metaIcon.classList.replace("dn", "db-ns");
      };
    }
  }
}

function toggleArticlesTip() {
  const tipToggler = document.getElementById("js--articles-tip-toggler");
  const tip = document.getElementById("js--articles-tip");
  if (tipToggler && tip) {
    tipToggler.onclick = () => {
      tip.classList.toggle("dn");
    };
  }
}

function addPrevNextPageKeyHandlers() {
  const prevLink: HTMLAnchorElement | null = document.querySelector(
    "a#prev-article-page-link"
  );
  const nextLink: HTMLAnchorElement | null = document.querySelector(
    "a#next-article-page-link"
  );
  if (prevLink || nextLink) {
    document.addEventListener("keydown", function (e) {
      if (e.code === "ArrowLeft" && prevLink) {
        document.location.href = prevLink.href;
      }
      if (e.code === "ArrowRight" && nextLink) {
        document.location.href = nextLink.href;
      }
    });
  }
}

export {
  initSrollIndicator,
  initToggleRaw,
  restoreSidebarScrollPos,
  saveSidebarScrollPos,
  toggleMetaDialog,
  toggleArticlesTip,
  isNSPage,
  isNSOfflinePage,
  isProjectDocumentationPage,
  addPrevNextPageKeyHandlers
};
