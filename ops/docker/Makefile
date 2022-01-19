CLJDOC_VERSION := $(shell ./../../script/version.sh)
DOCKER_IMAGE := cljdoc/cljdoc

.PHONY: clean image

# Instead of running docker build inside the root directory we have a
# small script that packages all relevant files into a zipfile. This
# makes the docker context very predictable and removes the need for
# separate .dockerignore files

clean:
	rm -rf ./../../target/cljdoc.zip cljdoc-build

../../target/cljdoc.zip:
	./../../script/package

cljdoc-build: ../../target/cljdoc.zip
	unzip ../../target/cljdoc.zip -d cljdoc-build/

image: clean cljdoc-build
	cp Dockerfile cljdoc-build/
	docker build --no-cache -t $(DOCKER_IMAGE):$(CLJDOC_VERSION) cljdoc-build/ && \
          docker tag $(DOCKER_IMAGE):$(CLJDOC_VERSION) $(DOCKER_IMAGE):latest
	rm -rf $^
