#!/bin/bash

set -euxo pipefail

: "${BENCHMARK_DIR:?BENCHMARK_DIR is required}"
: "${PR_HEAD_SHA:?PR_HEAD_SHA is required}"
: "${REPO_NAME:?REPO_NAME is required}"

cd -- "$BENCHMARK_DIR"
mkdir -- pr
cd -- pr
git clone --no-checkout "https://github.com/$REPO_NAME.git" NullAway
git -C NullAway checkout --detach "$PR_HEAD_SHA"
cd -- NullAway

./gradlew jmh --no-daemon
