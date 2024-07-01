FROM rust:slim-bookworm AS libdcgcv

COPY . /db
WORKDIR /db

RUN apt-get update && apt-get install -y \
	build-essential \
	libopencv-dev \
	clang

RUN make all


FROM clojure:tools-deps-bookworm-slim

COPY . /db
WORKDIR /db

COPY --from=libdcgcv /db/resources/libdcgcv.so /db/resources/libdcgcv.so

RUN apt-get update && apt-get install -y \
	libopencv-dev

RUN clojure -Stree

CMD clojure -X:ingest
