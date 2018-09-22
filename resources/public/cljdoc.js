"use strict"

var NSPage = document.querySelector(".ns-page")

if (NSPage) {
    initSrollIndicator()
    initToggleRaw()
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
    var toggles = Array.from(document.querySelectorAll(".toggle-raw"))

    function addToggleHandlers() {
        toggles.forEach((el, idx) => {
            el.addEventListener("click", function () {
                var parent = el.parentElement
                var markdown = parent.querySelector(".markdown")
                var raw = parent.querySelector(".raw")
                if (markdown.classList.contains("hide")) {
                    markdown.classList.remove("hide")
                    raw.classList.add("hide")
                } else {
                    markdown.classList.add("hide")
                    raw.classList.remove("hide")
                }
            })
        })
    }
    addToggleHandlers()
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

(function() {
  var scrollPosData = JSON.parse(localStorage.getItem("sidebarScrollPos"))
  var page = window.location.pathname.split("/").slice(0,5).join("/")

  if (scrollPosData && page == scrollPosData.page) {
    Array.from(document.querySelectorAll(".js--sidebar"))[0].scrollTop = scrollPosData.scrollTop
  }

  localStorage.removeItem("sidebarScrollPos")
})();
