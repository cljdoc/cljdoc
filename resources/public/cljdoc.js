"use strict"

var NSPage = document.querySelector(".ns-page")

if (NSPage) {
  initSrollIndicator()
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
        if (isElementVisible(sidebarScrollView, defItem) === false) {
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
