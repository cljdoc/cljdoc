FROM openjdk:8-jre-alpine

ENV CLOJURE_VERSION=1.10.3.822

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
# Docker JDK8 Options taken from https://royvanrijn.com/blog/2018/05/java-and-docker-memory-limits/
ENTRYPOINT ["clojure", "-J-XX:+UnlockExperimentalVMOptions", "-J-XX:+UseCGroupMemoryLimitForHeap", "-M", "-m", "cljdoc.server.system"]
