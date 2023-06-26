#!/bin/bash

BRANCH_NAME=$1

# Setup Directory and Copy Bash Scripts
mkdir $BRANCH_NAME
scp ./.github/workflows/run_pr_benchmarks.sh root@instance-1:$BRANCH_NAME/
scp ./.github/workflows/run_main_benchmarks.sh root@instance-1:$BRANCH_NAME/

# Run JMH benchmarks for PR Branch
chmod +x $BRANCH_NAME/run_pr_benchmarks.sh
$BRANCH_NAME/run_pr_benchmarks.sh

# Copy exported PR benchmark file from Google Cloud Compute Engine
scp root@instance-1:$BRANCH_NAME/pr/NullAway/jmh/build/results/jmh/results.txt ./pr_text.txt

# Run JMH benchmarks for Main Branch
chmod +x $BRANCH_NAME/run_main_benchmarks.sh
$BRANCH_NAME/run_main_benchmarks.sh

# Copy exported Main Benchmark file from Google Cloud Compute Engine 
scp root@instance-1:$BRANCH_NAME/main/NullAway/jmh/build/results/jmh/results.txt ./main_text.txt

# Cleanup
rm -r -f $BRANCH_NAME

# Remove this script
rm -- "$0"
