#!/bin/bash -eux

cd "$BRANCH_NAME/" 
mkdir main
cd main/ 
git clone git@github.com:Uber/NullAway.git
cd NullAway/ 

./gradlew jmh --no-daemon
