FROM adzerk/boot-clj:latest
ADD ./ /cljdoc
WORKDIR /cljdoc
# Pull deps in the build phase, this takes awhile
RUN boot show -d
RUN boot analysis-deps show -d
VOLUME ["/cljdoc/target"]
ENTRYPOINT ["/usr/bin/boot"]
