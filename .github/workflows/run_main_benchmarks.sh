#!/bin/bash -eux

cd "$BRANCH_NAME/" 
mkdir main
cd main/ 
git clone https://github.com/uber/NullAway.git
cd NullAway/ 

./gradlew jmh --no-daemon
