CLJDOC_VERSION := $(shell ./../../script/version.sh)

.PHONY: image

../../target/cljdoc.zip:
	./../../script/package

cljdoc-build: ../../target/cljdoc.zip
	unzip ../../target/cljdoc.zip -d cljdoc-build/

image: cljdoc-build
	cp Dockerfile cljdoc-build/
	docker build -t cljdoc/cljdoc:$(CLJDOC_VERSION) cljdoc-build/
	rm -rf $^
