function isNSPage() {
  return document.querySelector(".ns-page");
}

function isProjectDocumentationPage() {
  let pathSegs = window.location.pathname.split("/");
  return pathSegs.length >= 5 && pathSegs[1] == "d";
}

function initSrollIndicator() {
  var mainScrollView = document.querySelector(".js--main-scroll-view");
  var sidebarScrollView = document.querySelector(
    ".js--namespace-contents-scroll-view"
  );
  var defBlocks = Array.from(document.querySelectorAll(".def-block"));
  var defItems = Array.from(document.querySelectorAll(".def-item"));

  function isElementVisible(container, el) {
    var { y: etop, height } = el.getBoundingClientRect(),
      ebottom = etop + height,
      cbottom = window.innerHeight,
      ctop = cbottom - container.clientHeight;
    return etop <= cbottom && ebottom >= ctop;
  }

  function drawScrollIndicator() {
    defBlocks.forEach((el, idx) => {
      var defItem = defItems[idx];
      if (isElementVisible(mainScrollView, el)) {
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

  mainScrollView.addEventListener("scroll", drawScrollIndicator);

  drawScrollIndicator();
}

function initToggleRaw() {
  let toggles = Array.from(document.querySelectorAll(".js--toggle-raw"));

  function addToggleHandlers() {
    toggles.forEach(el => {
      el.addEventListener("click", function() {
        let parent = el.parentElement;
        let markdowns = parent.querySelectorAll(".markdown");
        let raws = parent.querySelectorAll(".raw");
        markdowns.forEach((markdown, idx) => {
          let raw = raws[idx];
          if (markdown.classList.contains("dn")) {
            markdown.classList.remove("dn");
            raw.classList.add("dn");
            el.innerText = "raw docstring";
          } else {
            markdown.classList.add("dn");
            raw.classList.remove("dn");
            el.innerText = "formatted docstring";
          }
        });
      });
    });
  }
  addToggleHandlers();
}

function restoreSidebarScrollPos() {
  var scrollPosData = JSON.parse(localStorage.getItem("sidebarScrollPos"));
  var page = window.location.pathname
    .split("/")
    .slice(0, 5)
    .join("/");

  if (scrollPosData && page == scrollPosData.page) {
    Array.from(document.querySelectorAll(".js--main-sidebar"))[0].scrollTop =
      scrollPosData.scrollTop;
  }

  localStorage.removeItem("sidebarScrollPos");
}

function toggleMetaDialog() {
  if (document.querySelector(".js--main-scroll-view")) {
    document.getElementById("js--meta-icon").onclick = function() {
      document.getElementById("js--meta-icon").classList.replace("db-ns", "dn");
      document
        .getElementById("js--meta-dialog")
        .classList.replace("dn", "db-ns");
    };

    document.getElementById("js--meta-close").onclick = function() {
      document
        .getElementById("js--meta-dialog")
        .classList.replace("db-ns", "dn");
      document.getElementById("js--meta-icon").classList.replace("dn", "db-ns");
    };
  }
}

function addpPrevNextPageKeyHandlers() {
  const prevLink = document.getElementById("prev-article-page-link");
  const nextLink = document.getElementById("next-article-page-link");
  if (prevLink || nextLink) {
    document.addEventListener("keydown", function(e) {
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
  toggleMetaDialog,
  isNSPage,
  isProjectDocumentationPage,
  addpPrevNextPageKeyHandlers
};
