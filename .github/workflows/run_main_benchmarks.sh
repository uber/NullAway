#!/bin/bash

cd "$BRANCH_NAME/" || exit
mkdir main
cd main/ || exit
git clone git@github.com:Uber/NullAway.git
cd NullAway/ || exit

./gradlew jmh --no-daemon
