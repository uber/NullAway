#!/bin/bash

# Assign command line argument to a variable
CMD="$1"

# Setup Directory and Copy Bash Scripts
chmod +x ./.github/workflows/gcloud_ssh.sh
./.github/workflows/gcloud_ssh.sh "export BRANCH_NAME=${BRANCH_NAME} && mkdir $BRANCH_NAME"
gcloud compute scp ./.github/workflows/run_pr_benchmarks.sh root@instance-1:$BRANCH_NAME/ --zone=us-central1-a
gcloud compute scp ./.github/workflows/run_main_benchmarks.sh root@instance-1:$BRANCH_NAME/ --zone=us-central1-a

# Run JMH benchmarks for PR Branch
./.github/workflows/gcloud_ssh.sh " export BRANCH_NAME=${BRANCH_NAME} && export REPO_NAME=${REPO_NAME} && chmod +x $BRANCH_NAME/run_pr_benchmarks.sh && $BRANCH_NAME/run_pr_benchmarks.sh"

# Copy exported PR benchmark file from Google Cloud Compute Engine
gcloud compute scp root@instance-1:$BRANCH_NAME/pr/NullAway/jmh/build/results/jmh/results.txt ./pr_text.txt --zone=us-central1-a

# Run JMH benchmarks for Main Branch
./.github/workflows/gcloud_ssh.sh " export BRANCH_NAME=${BRANCH_NAME} && chmod +x $BRANCH_NAME/run_main_benchmarks.sh && $BRANCH_NAME/run_main_benchmarks.sh "

# Copy exported Main Benchmark file from Google Cloud Compute Engine 
gcloud compute scp root@instance-1:$BRANCH_NAME/main/NullAway/jmh/build/results/jmh/results.txt ./main_text.txt --zone=us-central1-a
