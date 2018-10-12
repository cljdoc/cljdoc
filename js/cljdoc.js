function isNSPage() {
  return document.querySelector(".ns-page")
}

function isDocPage() {
  return document.querySelector("#doc-html")
}

function initSrollIndicator() {
  var mainScrollView = document.querySelector(".main-scroll-view")
  var sidebarScrollView = document.querySelector(".sidebar-scroll-view")
  var defBlockTitles = Array.from(document.querySelectorAll(".def-block-title"))
  var defItems = Array.from(document.querySelectorAll(".def-item"))

  function isElementVisible(container, el) {
    var st = container.scrollTop
    var y = el.getBoundingClientRect().y
    var wh = window.innerHeight
    var h = container.clientHeight
    var top = wh - h
    return y > top && y < wh
  }

  function drawScrollIndicator() {
    defBlockTitles.forEach((el, idx) => {
      var defItem = defItems[idx]
      if (isElementVisible(mainScrollView, el)) {
        defItem.classList.add("scroll-indicator")
        if (idx === 0) {
          sidebarScrollView.scrollTop = 1
        } else if (isElementVisible(sidebarScrollView, defItem) === false) {
          defItem.scrollIntoView()
        }
      } else {
        defItem.classList.remove("scroll-indicator")
      }
    })
  }

  mainScrollView.addEventListener("scroll", drawScrollIndicator)

  drawScrollIndicator()
}

function initToggleRaw() {
  let toggles = Array.from(document.querySelectorAll(".js--toggle-raw"));

  function addToggleHandlers() {
    toggles.forEach((el) => {
      el.addEventListener("click", function () {
        let parent = el.parentElement;
        let markdowns = parent.querySelectorAll(".markdown");
        let raws = parent.querySelectorAll(".raw");
        markdowns.forEach((markdown, idx) => {
          let raw = raws[idx];
          if (markdown.classList.contains("dn")) {
            markdown.classList.remove("dn");
            raw.classList.add("dn");
            el.innerText = "raw docstring"
          } else {
            markdown.classList.add("dn");
            raw.classList.remove("dn");
            el.innerText = "formatted docstring"
          }
        })
      })
    })
  }
  addToggleHandlers()
}

function initDocTitle () {
    var mainScrollView = document.querySelector(".main-scroll-view")
    var docHtml = document.querySelector("#doc-html")
    var docHeaders = Array.from(docHtml.querySelectorAll("h1, h2, h3, h4, h5, h6"))
    var docTitle = document.querySelector("#js--doc-title")
    var lastIndex = null

    function isBelow(container, element) {
        var containerTop = container.getBoundingClientRect().top
        var elementTop = element.getBoundingClientRect().top - 1
        // minus one for anchors to be correct
        return containerTop > elementTop
    }

    function immediatelyAbove (container, elements) {
        for (let j = 0; j < elements.length - 1; j++) {
            if (!isBelow(container, elements[j+1])) {
                return j
            }
        }
        return elements.length - 1
    }

    function changeTitle() {
        var index = immediatelyAbove(mainScrollView, docHeaders)
        if (index !== lastIndex) {
            var anchor = docHeaders[index].querySelector('a')
            var url = new URL(anchor.href)
            docTitle.innerText = anchor.innerText
            docTitle.href = url.hash
            // set last index so it doesn't trigger super often
            lastIndex = index
        }
    }
    mainScrollView.addEventListener("scroll", changeTitle)
    changeTitle()
}

function restoreSidebarScrollPos() {
  var scrollPosData = JSON.parse(localStorage.getItem("sidebarScrollPos"))
  var page = window.location.pathname.split("/").slice(0,5).join("/")

  if (scrollPosData && page == scrollPosData.page) {
    Array.from(document.querySelectorAll(".js--sidebar"))[0].scrollTop = scrollPosData.scrollTop
  }

  localStorage.removeItem("sidebarScrollPos")
}

export { initSrollIndicator,
         initToggleRaw,
         initDocTitle,
         restoreSidebarScrollPos,
         isNSPage,
         isDocPage }
