FROM eclipse-temurin:17-alpine

WORKDIR /tmp

RUN apk add --update --no-cache bash curl git

RUN curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh \
    && chmod +x linux-install.sh \
    && ./linux-install.sh

RUN clojure -Sdescribe

RUN apk add sqlite curl

WORKDIR /app

COPY deps.edn /app
RUN clojure -Stree -M:cli

COPY . /app

EXPOSE 8000

ENTRYPOINT ["script/docker-entrypoint.sh"]
