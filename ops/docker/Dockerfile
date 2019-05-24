FROM openjdk:8-jre-alpine

ENV CLOJURE_VERSION=1.9.0.397

WORKDIR /tmp

RUN apk add --update --no-cache bash curl

RUN curl -O https://download.clojure.org/install/linux-install-$CLOJURE_VERSION.sh \
    && chmod +x linux-install-$CLOJURE_VERSION.sh \
    && ./linux-install-$CLOJURE_VERSION.sh

RUN clojure -e "(clojure-version)"

RUN apk add sqlite curl

WORKDIR /app

COPY deps.edn /app
RUN clojure -R:cli -Stree

COPY . /app

EXPOSE 8000
# Docker JDK8 Options taken from https://royvanrijn.com/blog/2018/05/java-and-docker-memory-limits/
ENTRYPOINT ["clojure", "-J-XX:+UnlockExperimentalVMOptions", "-J-XX:+UseCGroupMemoryLimitForHeap", "-m", "cljdoc.server.system"]
