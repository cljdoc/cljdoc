# 14. Add Support For Examples

Date: 2018-10-14

## Status

Superseded by [ADR #0015](0015-cancel-work-on-examples.md)

## Context

Examples are an often suggested feature for cljdoc that could enable library authors and the community to further guide library users.

## Decision

Add support for examples to cljdoc. Allow libary users to provide examples through their Git repository but also run a community-maintained examples repository where examples can be maintained outside of a project's own Git repository. 

## Consequences

The two different sources for examples (library author vs. community) pose some additional challenges, summarised in this table: 


| Community Examples                             | Library Examples                  |
| ---------------------------------------------- | --------------------------------- |
| User contributed                               | Written by library author(s)      |
| No perfect mapping to releases                 | Linkable to releases via Git      |
| Continously evolving dataset                   | Separate dataset for each release |
| Stored in single `community-examples` Git repo | Stored within library repo        |

Those differences require slight adjustment in the way examples are specified (most importantly to link examples to version ranges).

A sketch at how this could be addressed can be found in the Appendix of this ADR.

## Appendix

### Process

[Issue #70](https://github.com/cljdoc/cljdoc/issues/70). Branch is [`70-examples`](https://github.com/cljdoc/cljdoc/tree/70-examples).

- [x] Define Markdown file format (YAML front matter)
- [x] Extend git-repo stuff to read examples
  - [x] Extract contributors to example file
  - [x] Extract date of first commit this file was added with
- [x] Define columns & add table that links examples to a specific version
  - [x] Contributor(s), Date added, Example (md)
- [ ] Integrate examples into cache bundles
- [ ] Integrate examples into UI


### Library Examples

```
---
for-var: clojure.core/conj
for-namespace: clojure.core
---
For lists `conj` adds items to the front of the list
\```
(conj '(1 2 3) 0)
;;=> (0 1 2 3)
\```
```


```markdown
---
for-var: clojure.core/conj
---

For lists `conj` adds items to the front of the list
\```
(conj '(1 2 3) 0)
;;=> (0 1 2 3)
\```
```

### Library Example in Multi-Module Repo

```markdown
---
for-artifact: metosin/reitit-ring
for-var: reitit.ring/router
---

\```
(router
  [\"/api\" {:middleware [wrap-format wrap-oauth2]}
  [\"/users\" {:get get-user
               :post update-user
               :delete {:middleware [wrap-delete]
               :handler delete-user}}]])
\```
```

### Community Contributed Example

The following would be maintained in a single Git repository containing all community-contributed examples. The directory structure could roughly look like this: 
`/examples/$group_id/$artifact_id/$example_name.markdown`

```markdown
---
version-range: 0.1.0..
for-var: my.lib/fn
---

How to do this and that.
```

```markdown
---
version-range: 0.1.0..0.3.0
for-var: my.lib/fn
---

Will only be shown for versions 0.1.0 to 0.3.0.
How to do this and that. 
```



