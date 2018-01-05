### What is this

See this [ClojureVerse thread](https://clojureverse.org/t/creating-a-central-documentation-repository-website-codox-complications/1287/) 
about a central documentation hub for Clojure similar to Elixir's https://hexdocs.pm/.


You can run what's inside by running

```
boot build-docs --project org.martinklepsch/derivatives --version 0.2.0
```

#### NEXT STEPS (mostly getting more information from Git repo)
- [ ] read Github URL from pom.xml or Clojars
- [ ] clone repo, copy `doc` directory, provide to codox
- [ ] derive source-uri (probably needs parsing of project.clj or build.boot or perhaps we can derive source locations by overlaying jar contents)
- [ ] figure out what other metadata should be imported

#### LONG SHOTS
- [ ] think about discovery of projects with same group-id
- [ ] think about how something like dynadoc (interactive docs) could be integrated
