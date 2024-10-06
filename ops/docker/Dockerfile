FROM eclipse-temurin:23-alpine

WORKDIR /tmp

RUN apk add --update --no-cache bash curl git tar zstd procps

RUN curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh \
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

EXPOSE 8000

ENTRYPOINT ["script/docker_entrypoint.clj"]
