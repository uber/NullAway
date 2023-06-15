#!/bin/bash

cd $BRANCH_NAME/
mkdir main
cd main/
git clone git@github.com:armughan11/NullAway.git
cd NullAway/
./gradlew jmh
