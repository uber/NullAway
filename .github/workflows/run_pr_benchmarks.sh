#!/bin/bash

cd "$BRANCH_NAME/" || exit
mkdir pr
cd pr/ || exit
git clone --branch "$BRANCH_NAME" --single-branch git@github.com:"$REPO_NAME".git NullAway
cd NullAway/ || exit

./gradlew jmh --no-daemon
