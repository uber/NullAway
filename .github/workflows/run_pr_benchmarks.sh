#!/bin/bash

cd $BRANCH_NAME/
mkdir pr
cd pr/
git clone --branch $BRANCH_NAME --single-branch git@github.com:Uber/NullAway.git
cd NullAway/
./gradlew jmh
