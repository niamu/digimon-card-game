FROM clojure:tools-deps-bookworm-slim

COPY db/ /db
COPY api/ /api
WORKDIR /api

RUN mkdir -p /api/resources
RUN clojure -Stree

CMD clojure -X:serve
