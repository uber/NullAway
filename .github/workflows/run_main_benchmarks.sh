#!/bin/bash

set -euxo pipefail

: "${BENCHMARK_DIR:?BENCHMARK_DIR is required}"

cd -- "$BENCHMARK_DIR"
mkdir -- main
cd -- main
git clone https://github.com/uber/NullAway.git
cd -- NullAway

./gradlew jmh --no-daemon
