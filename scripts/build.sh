#!/bin/sh

set -exuo pipefail

docker build \
       -t graalvm \
       -f scripts/Dockerfile \
       .

mkdir -p resources/binaries/

docker run \
       --rm \
       -v $HOME/.m2:/root/.m2 \
       -v $(pwd):/app \
       -w /app \
       graalvm \
       ./scripts/native-image.sh || true

mv target/dcg.linux resources/binaries/

clojure -M:native-image --image-name dcg.macos
mv target/dcg.macos resources/binaries/

clojure -M:cljs
mv resources/public/js/dcg.js docs/js/dcg.js
