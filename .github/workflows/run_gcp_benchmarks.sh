#!/bin/bash

# This script is responsible for running benchmarks for a GitHub pull request and the main branch on Google Cloud Compute Engine (GCCE).


# Setting up a new directory named after the branch on GCCE and copying bash scripts into this directory
# The ssh command is made executable and then run with a command to export the branch name and make a new directory on the GCCE
chmod +x ./.github/workflows/gcloud_ssh.sh
./.github/workflows/gcloud_ssh.sh "export BRANCH_NAME=${BRANCH_NAME} && mkdir $BRANCH_NAME"

# Using gcloud compute scp to copy the bash scripts that will run the benchmarks onto the GCCE
gcloud compute scp ./.github/workflows/run_pr_benchmarks.sh root@instance-1:$BRANCH_NAME/ --zone=us-central1-a
gcloud compute scp ./.github/workflows/run_main_benchmarks.sh root@instance-1:$BRANCH_NAME/ --zone=us-central1-a

# Running the benchmark script for the pull request branch on GCCE
./.github/workflows/gcloud_ssh.sh " export BRANCH_NAME=${BRANCH_NAME} && export REPO_NAME=${REPO_FULL_NAME} && chmod +x $BRANCH_NAME/run_pr_benchmarks.sh && $BRANCH_NAME/run_pr_benchmarks.sh"

# Copying the benchmark results from GCCE back to the Github runner for the PR branch
gcloud compute scp root@instance-1:$BRANCH_NAME/pr/NullAway/jmh/build/results/jmh/results.txt ./pr_text.txt --zone=us-central1-a

# Running the benchmark script for the main branch on GCCE
./.github/workflows/gcloud_ssh.sh " export BRANCH_NAME=${BRANCH_NAME} && chmod +x $BRANCH_NAME/run_main_benchmarks.sh && $BRANCH_NAME/run_main_benchmarks.sh "

# Copying the benchmark results from GCCE back to the Github runner for the main branch
gcloud compute scp root@instance-1:$BRANCH_NAME/main/NullAway/jmh/build/results/jmh/results.txt ./main_text.txt --zone=us-central1-a
