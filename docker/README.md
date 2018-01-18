# clj-docs in docker

This dockerfile allows us to generate docs for a jar in isolation, evaluating
untrusted code in a contained environment.

## Building

To build this container, run:

`$ docker build -t clj-docs:latest -f docker/Dockerfile .`

## Running

To generate documents for a jar, run:

`$ docker run -it clj-docs:latest -p {clojure-project-name} -v {clojure-project-version} target`

The clojure project name is `{project-group-id}/{project-name}`, for example: `org.clojure/clojure`

The clojure project version is a clojure project version string, for example `1.0.0`

## Accessing Generated Docs

This mounts a directory on the host machine into the docker container, where generated
files will be placed. This can be done by running the following

`$ docker run --mount type=bind,source={directory-to-mount},destination=/clj-docs/target clj-docs:latest -p {clojure-project-name} -v {clojure-project-version} target`
