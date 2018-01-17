FROM adzerk/boot-clj:latest
ADD ./ /boot-clj
WORKDIR /boot-clj
# Pull deps in the build phase, this takes awhile
RUN boot show -d
ENTRYPOINT ["/usr/bin/boot"]
