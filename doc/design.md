# Design

We use [Tachyons](http://tachyons.io/) for most CSS needs. I'm still planning to write an ADR on that — in the meantime refer to Tachyon's creator @mrmrs' blog post [Scalable CSS](http://mrmrs.github.io/writing/2016/03/24/scalable-css/).

### Ideas for improvements

- General
  - Think about some UI to switch between versions and view other supplemental information, docs.rs solves this nicely with a dropdown:
    ![docs.rs dropdown](https://user-images.githubusercontent.com/97496/35530810-6f0b1b00-0535-11e8-833d-277bc61977a6.png)

  - Also we might want some space for anything not related to the current project but cljdoc itself
  - Two top bars might help but would take a lot of screen space.
- API browsing
  - Codox' [nested sidebars](http://weavejester.github.io/compojure/compojure.core.html) are nice because:
    - They allow you to see namespace and var-list at the same time
    - They have this neat scrolling indicator
  - Currently we are not rendering protocol member methods
  - improve overall visual hierarchy of `def-block`
- Article view
  - Some documents might be long, similar to the Codox
    scroll-indicator we could have a little UI at the top that
    indicates what section you're in

    ![cljdoc section viewer](https://user-images.githubusercontent.com/97496/35530594-d6fffc40-0534-11e8-975a-869cb1f88e0f.png)

  - With some JS users might even be able to click on it and navigate sections in a document
  - The sections would need to be inferred from the rendered HTML
  - There is little styling done for rendered Markdown or Asciidoc. We could style things using plain CSS or adding [tachyons](http://tachyons.io/) classes to elements.
  - Syntax highlighting is not implemented yet
