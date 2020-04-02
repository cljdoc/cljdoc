# Inclusion of Non-Authoritative Documentation

During a discussion with Reid we realised that the inclusion of non-authoritative documentation might be quite feasible and have a positive impact on overall documentation.

**Non-authoritative documentation** describes anything that is not authored or vetted by the maintainers of the project that is being documented. In the past this has been a common issue with `clojure.core`. Projects like ClojureDocs filled that gap by providing a platform where everyone can contribute documentation.

For examples we could store them just like any other but add a flag `:not-authoritative?`. For docstrings this would work directly since there is only one doctsring per `def` or `ns` perhaps an extra field `:non-authoritative-doc` could contain a list of community provided docstrings.

All keys subject to debate.
