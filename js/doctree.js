function isDocLink(linkEl) {
  return linkEl.pathname.startsWith("/d/");
}

export function hideNestedArticles() {
  let currentPath = location.pathname;
  let articleLinks = Array.from(document.querySelectorAll(".js--articles a"));

  function hideNested(link) {
    if (!currentPath.startsWith(link.pathname) && link.nextSibling) {
      link.nextSibling.classList.add("dn");
    }
  }

  articleLinks.filter(isDocLink).map(hideNested);
}

export function showNestedArticles() {
  let hiddenLinks = Array.from(
    document.querySelectorAll(".js--articles ul.dn")
  );
  hiddenLinks.map(n => n.classList.remove("dn"));
}
