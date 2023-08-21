#!/bin/bash -eux

cd "$BRANCH_NAME/" 
mkdir pr
cd pr/ 
git clone --branch "$BRANCH_NAME" --single-branch git@github.com:"$REPO_NAME".git NullAway
cd NullAway/ 

./gradlew jmh --no-daemon
