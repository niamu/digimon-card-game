#!/bin/sh

set -exuo pipefail

docker build \
       -t graalvm \
       -f scripts/Dockerfile \
       .

docker run \
       --rm \
       -v $HOME/.m2:/root/.m2 \
       -v $(pwd):/app \
       -w /app \
       graalvm \
       ./scripts/native-image.sh || true
