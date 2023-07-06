#!/bin/bash

# This script is responsible for cloning a specific branch (the PR branch) from a GitHub repository and running Java Microbenchmark Harness (JMH) benchmarks.

# Change directory to the one named after the branch name. 
cd $BRANCH_NAME/

# Create a new directory called 'pr' and navigate into it
mkdir pr
cd pr/

# Clone the specific branch from the GitHub repository. 
# The repository and branch are specified by the REPO_FULL_NAME and BRANCH_NAME variables.
git clone --branch $BRANCH_NAME --single-branch git@github.com:$REPO_FULL_NAME.git

# Change directory into the newly cloned repository
cd NullAway/

# Run the JMH benchmarks using the Gradle Wrapper. 
./gradlew jmh
