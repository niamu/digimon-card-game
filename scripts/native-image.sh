#!/bin/sh

clojure -M:native-image --graalvm-home=/usr/lib/graalvm/ --image-name dcg && \
    chown -R $(id -u):$(id -g) ./target
