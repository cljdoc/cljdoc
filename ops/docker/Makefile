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
	docker build -t cljdoc/cljdoc:$(CLJDOC_VERSION) cljdoc-build/ && \
          docker tag cljdoc/cljdoc:$(CLJDOC_VERSION) cljdoc/cljdoc:latest
	rm -rf $^
