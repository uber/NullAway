#!/bin/bash

# This script is responsible for cloning the main repository from GitHub and running Java Microbenchmark Harness (JMH) benchmarks.

# Change directory to the one named after the branch name. 
cd $BRANCH_NAME/

# Create a new directory called 'main' and navigate into it
mkdir main
cd main/

# Clone the main repository from GitHub
git clone git@github.com:Uber/NullAway.git

# Change directory into the newly cloned repository
cd NullAway/

# Run the JMH benchmarks using the Gradle Wrapper. 
./gradlew jmh
