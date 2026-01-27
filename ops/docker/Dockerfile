FROM eclipse-temurin:25-alpine

WORKDIR /tmp

RUN apk add --update --no-cache bash curl git tar zstd procps

# There's a problem with 1.12.4.1597, see: https://ask.clojure.org/index.php/14906/tdeps-269-checksums-should-use-sha256sum-instead-of-shasum
# Switch back to latest when resolved.
RUN curl -L -O https://github.com/clojure/brew-install/releases/download/1.12.4.1582/linux-install.sh \
    && chmod +x linux-install.sh \
    && ./linux-install.sh

RUN bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)

# Force download of clojure tools
RUN bb clojure -Spath

RUN clojure -Sdescribe

RUN apk add sqlite

WORKDIR /app

COPY deps.edn /app
RUN clojure -Stree -M:cli

COPY . /app

# CLJDOC_DATA_DIR dependencies:
# - can't change without breaking docs for running cljdoc locally (via docker)
# - must match volumes mapping in cljdoc.jobspec.edn
ENV CLJDOC_DATA_DIR=/app/data
ENV CLJDOC_LOG_FILE=/app/data/cljdoc.log

EXPOSE 8000

ENTRYPOINT ["script/docker_entrypoint.clj"]
