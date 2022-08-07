FROM eclipse-temurin:17-alpine

ENV CLOJURE_VERSION=1.11.1.1155

WORKDIR /tmp

RUN apk add --update --no-cache bash curl git

RUN curl -O https://download.clojure.org/install/linux-install-$CLOJURE_VERSION.sh \
    && chmod +x linux-install-$CLOJURE_VERSION.sh \
    && ./linux-install-$CLOJURE_VERSION.sh

RUN clojure -Sdescribe

RUN apk add sqlite curl

WORKDIR /app

COPY deps.edn /app
RUN clojure -Stree -M:cli

COPY . /app

EXPOSE 8000

ENTRYPOINT ["script/docker-entrypoint.sh"]
