function isDocLink(linkEl: HTMLAnchorElement) {
  return linkEl.pathname.startsWith("/d/");
}

export function hideNestedArticles() {
  let currentPath = location.pathname;
  let articleLinks: HTMLAnchorElement[] = Array.from(
    document.querySelectorAll(".js--articles a")
  );

  function hideNested(link: HTMLAnchorElement) {
    if (!currentPath.startsWith(link.pathname) && link.nextElementSibling) {
      link.nextElementSibling.classList.add("dn");
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
