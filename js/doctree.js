export function hideNestedArticles() {
  let currentPath = location.pathname;
  let articleLinks = Array.from(document.querySelectorAll(".js--articles a"));

  function hideNested(link) {
    if (!currentPath.startsWith(link.pathname) && link.nextSibling) {
      link.nextSibling.classList.add("dn");
    }
  }

  function isDocLink(link) {
    return link.pathname.startsWith("/d/");
  }

  articleLinks.filter(isDocLink).map(hideNested);
}
