CLJDOC_VERSION := $(shell ./../../script/version.sh)

.PHONY: image

../../.dockerignore: .dockerignore
	cp .dockerignore $@

../../Dockerfile: Dockerfile
	cp Dockerfile $@

image: ../../.dockerignore ../../Dockerfile
	docker build -t cljdoc/cljdoc:$(CLJDOC_VERSION) ../../
	rm $^
