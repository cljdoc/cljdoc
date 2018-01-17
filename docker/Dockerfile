FROM adzerk/boot-clj:latest
ADD ./ /clj-docs
WORKDIR /clj-docs
# Pull deps in the build phase, this takes awhile
RUN boot show -d
ENTRYPOINT ["/usr/bin/boot"]
