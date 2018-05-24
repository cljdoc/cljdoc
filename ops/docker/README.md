# cljdoc in docker

This dockerfile allows us to generate docs for a jar in isolation, evaluating
untrusted code in a contained environment.

## Building

To build this container, run the following **from the root of this repository**:

```sh
$ docker build -t cljdoc:latest -f docker/Dockerfile .
```

## Running

To generate documents for a `[bidi "2.1.3"]`, run:

```sh
$ docker run --rm cljdoc:latest build-docs --project bidi --version 2.1.3 target
```

## Accessing Generated Docs

This mounts a directory on the host machine into the docker container, where generated
files will be placed. This can be done by running the following

```sh
$ mkdir -p docker/docker-target
$ export DOCKER_TARGET=$(pwd)/docker/docker-target
$ docker run --rm --mount type=bind,source=$DOCKER_TARGET,destination=/cljdoc/target \
      cljdoc:latest build-docs --project bidi --version 2.1.3 target
```
