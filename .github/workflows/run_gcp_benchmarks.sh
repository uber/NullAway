#!/bin/bash

# This script is responsible for running benchmarks for a GitHub pull request and the main branch on Google Cloud Compute Engine (GCCE).


chmod +x ./.github/workflows/gcloud_ssh.sh
./.github/workflows/gcloud_ssh.sh "export BRANCH_NAME=${BRANCH_NAME} && mkdir $BRANCH_NAME"

# Using gcloud compute scp to copy the bash scripts that will run the benchmarks onto the GCCE
gcloud compute scp ./.github/workflows/run_pr_benchmarks.sh root@nullway-jmh:"$BRANCH_NAME/" --zone=us-central1-a
gcloud compute scp ./.github/workflows/run_main_benchmarks.sh root@nullway-jmh:"$BRANCH_NAME/" --zone=us-central1-a

# Running the benchmark script for the pull request branch and main branch on GCCE
./.github/workflows/gcloud_ssh.sh " export BRANCH_NAME=${BRANCH_NAME} && export REPO_NAME=${REPO_FULL_NAME} && chmod +x $BRANCH_NAME/run_pr_benchmarks.sh && $BRANCH_NAME/run_pr_benchmarks.sh && cd && chmod +x $BRANCH_NAME/run_main_benchmarks.sh && $BRANCH_NAME/run_main_benchmarks.sh"

# Copying the benchmark results from GCCE back to the Github runner for the PR branch
gcloud compute scp root@nullway-jmh:"$BRANCH_NAME/pr/NullAway/jmh/build/results/jmh/results.txt" ./pr_text.txt --zone=us-central1-a

# Copying the benchmark results from GCCE back to the Github runner for the main branch
gcloud compute scp root@nullway-jmh:"$BRANCH_NAME/main/NullAway/jmh/build/results/jmh/results.txt" ./main_text.txt --zone=us-central1-a
