import { SidebarScrollState } from "./types";

function isNSOverviewPage(): boolean {
  return !!document.querySelector(".ns-overview-page");
}

function isNSPage(): boolean {
  return !!document.querySelector(".ns-page");
}

function isNSOfflinePage(): boolean {
  return !!document.querySelector(".ns-offline-page");
}

function isProjectDocumentationPage(): boolean {
  const pathSegs = window.location.pathname.split("/");
  return pathSegs.length >= 5 && pathSegs[1] == "d";
}

function initSrollIndicator(): void {
  const mainScrollView = document.querySelector(".js--main-scroll-view");
  const sidebarScrollView = document.querySelector(
    ".js--namespace-contents-scroll-view"
  );
  const defBlocks = Array.from(document.querySelectorAll(".def-block"));
  const defItems = Array.from(document.querySelectorAll(".def-item"));

  function isElementVisible(container: Element, el: Element) {
    const { y: etop, height } = el.getBoundingClientRect(),
      ebottom = etop + height,
      cbottom = window.innerHeight,
      ctop = cbottom - container.clientHeight;
    return etop <= cbottom && ebottom >= ctop;
  }

  function drawScrollIndicator() {
    defBlocks.forEach((el: Element, idx) => {
      const defItem = defItems[idx];
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
  const toggles: HTMLElement[] = Array.from(
    document.querySelectorAll(".js--toggle-raw")
  );

  function addToggleHandlers() {
    toggles.forEach(el => {
      el.addEventListener("click", function () {
        const parent = el.parentElement;
        const markdowns = parent && parent.querySelectorAll(".markdown");
        const raws = parent && parent.querySelectorAll(".raw");
        markdowns &&
          markdowns.forEach((markdown, idx) => {
            const raw = raws && raws[idx];
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

/**
 * Returns lib and version portion of current location.
 * Example: /d/clj-commons/clj-yaml/1.0.27
 */
function libVersionPath() {
  return window.location.pathname.split("/").slice(0, 5).join("/");
}

/**
 * Returns true if element is out of view but can, in theory, be scrolled down to.
 */
function isElementOutOfView(elem: HTMLElement) {
  const rect = elem.getBoundingClientRect();
  const isOutOfView =
    rect.top > window.innerHeight || // Below the view
    rect.bottom < 0; // Above the view
  return isOutOfView;
}

/**
 * Cljdoc always loads a full page.
 * This means the sidebar nav scoll position needs to be restored/set.
 */
function restoreSidebarScrollPos() {
  const mainSidebar = document.querySelector(".js--main-sidebar");
  if (!mainSidebar) return;

  // Load any state saved by saveSidebarScrollPos
  const sidebarScrollState = JSON.parse(
    sessionStorage.getItem("sidebarScroll") || "null"
  );
  // and always wipe it, we don't want to remember it
  sessionStorage.removeItem("sidebarScroll");

  if (window.location.search) {
    // assume docset search
    return;
  }

  if (
    sidebarScrollState &&
    libVersionPath() == sidebarScrollState.libVersionPath
  ) {
    mainSidebar.scrollTop = sidebarScrollState.scrollTop;
  } else {
    const selectedElem: HTMLElement | null = mainSidebar.querySelector("a.b");
    if (selectedElem && isElementOutOfView(selectedElem)) {
      selectedElem.scrollIntoView({
        behavior: "instant",
        block: "start"
      });
    }
  }
}

/**
 * Support for restoreSidebarScrollPos
 *
 * When item in sidebar is clicked saves scroll pos and lib/version to session.
 */
function saveSidebarScrollPos() {
  const mainSidebar = document.querySelector(".js--main-sidebar");
  if (mainSidebar) {
    const anchorElems = mainSidebar.querySelectorAll("a");
    anchorElems.forEach(anchor => {
      anchor.addEventListener("click", function () {
        const scrollTop = mainSidebar.scrollTop;
        const data: SidebarScrollState = {
          libVersionPath: libVersionPath(),
          scrollTop: scrollTop
        };
        sessionStorage.setItem("sidebarScroll", JSON.stringify(data));
      });
    });
  }
}

function toggleMetaDialog() {
  if (document.querySelector(".js--main-scroll-view")) {
    const metaIcon = document.querySelector(
      "[data-id='cljdoc-js--meta-icon']"
    ) as HTMLElement;
    const metaDialog = document.querySelector(
      "[data-id='cljdoc-js--meta-dialog']"
    ) as HTMLElement;
    const metaClose = document.querySelector(
      "[data-id='cljdoc-js--meta-close']"
    ) as HTMLElement;

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
  const tipToggler = document.querySelector(
    "[data-id='cljdoc-js--articles-tip-toggler']"
  ) as HTMLElement;
  const tip = document.querySelector(
    "[data-id='cljdoc-js--articles-tip']"
  ) as HTMLElement;
  if (tipToggler && tip) {
    tipToggler.onclick = () => {
      tip.classList.toggle("dn");
    };
  }
}

function addPrevNextPageKeyHandlers() {
  const prevLink: HTMLAnchorElement | null = document.querySelector(
    "a[data-id='cljdoc-prev-article-page-link']"
  );
  const nextLink: HTMLAnchorElement | null = document.querySelector(
    "a[data-id='cljdoc-next-article-page-link']"
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
  isNSOverviewPage,
  isNSOfflinePage,
  isProjectDocumentationPage,
  addPrevNextPageKeyHandlers
};
