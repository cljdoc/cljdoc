FROM eclipse-temurin:25-alpine

WORKDIR /tmp

# System packages (should change rarely)
RUN apk add --update --no-cache bash curl git tar zstd procps sqlite

# Clojure CLI (changes rarely)
RUN curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh \
    && chmod +x linux-install.sh \
    && ./linux-install.sh

# Babashka (changes more frequently)
RUN bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)

# Create non‑root user (system account, no password) (syntax/behaviour a bit odd on Alpine)
RUN addgroup -g 1000 cljdoc && \
    adduser -u 1000 -G cljdoc -s /bin/sh -h /home/cljdoc -D cljdoc && \
    mkdir -p /home/cljdoc && \
    chown cljdoc:cljdoc /home/cljdoc

WORKDIR /app
RUN chown -R cljdoc:cljdoc /app

# Run under non-root user
USER cljdoc

# Trick to pre-download babashka ~/deps.clj tools
RUN bb clojure -version

# Copy deps.edn and download cljdoc dependencies
COPY --chown=cljdoc:cljdoc deps.edn /app
RUN clojure -P -M:cli

# Copy rest of cljdoc application
COPY --chown=cljdoc:cljdoc . /app

# Create data dir
RUN mkdir -p /app/data

# CLJDOC_DATA_DIR dependencies:
# - can't change without breaking docs for running cljdoc locally (via docker)
# - must match volumes mapping in cljdoc.jobspec.edn
ENV CLJDOC_DATA_DIR=/app/data
ENV CLJDOC_LOG_FILE=/app/data/cljdoc.log

EXPOSE 8000

ENTRYPOINT ["script/docker_entrypoint.clj"]
