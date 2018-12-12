# TODO consider moving that into a file that is packaged
# as part of script/package, e.g. resources-compiled/CLJDOC_VERSION
CLJDOC_VERSION := $(shell ./../../script/version.sh)

.PHONY: image

# Instead of running docker build inside the root directory we have a
# small script that packages all relevant files into a zipfile. This
# makes the docker context very predictable and removes the need for
# separate .dockerignore files

../../target/cljdoc.zip:
	./../../script/package

cljdoc-build: ../../target/cljdoc.zip
	unzip ../../target/cljdoc.zip -d cljdoc-build/

image: cljdoc-build
	cp Dockerfile cljdoc-build/
	docker build -t cljdoc/cljdoc:$(CLJDOC_VERSION) cljdoc-build/
	rm -rf $^
