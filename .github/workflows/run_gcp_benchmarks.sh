#!/bin/bash

# This script is responsible for running benchmarks for a GitHub pull request and the main branch on Google Cloud Compute Engine (GCCE).

set -euo pipefail

readonly PROJECT_ID="ucr-ursa-major-sridharan-lab"
readonly ZONE="us-central1-a"
readonly USER="root"
readonly INSTANCE="nullway-jmh"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR

: "${GITHUB_RUN_ID:?GITHUB_RUN_ID is required}"
: "${GITHUB_RUN_ATTEMPT:?GITHUB_RUN_ATTEMPT is required}"

if [[ ! "$GITHUB_RUN_ID" =~ ^[0-9]+$ || ! "$GITHUB_RUN_ATTEMPT" =~ ^[0-9]+$ ]]; then
  echo "GitHub run ID and attempt must be numeric" >&2
  exit 1
fi

readonly REMOTE_DIR="/root/nullaway-jmh-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"

if [[ "${1:-}" == "--cleanup" ]]; then
  bash "$SCRIPT_DIR/gcloud_ssh.sh" "rm -rf -- '$REMOTE_DIR'"
  exit
elif (( $# != 0 )); then
  echo "Usage: $0 [--cleanup]" >&2
  exit 1
fi

: "${PR_HEAD_SHA:?PR_HEAD_SHA is required}"
: "${REPO_FULL_NAME:?REPO_FULL_NAME is required}"

if [[ ! "$PR_HEAD_SHA" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "Invalid pull request head SHA: $PR_HEAD_SHA" >&2
  exit 1
fi
if [[ ! "$REPO_FULL_NAME" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
  echo "Invalid head repository name: $REPO_FULL_NAME" >&2
  exit 1
fi

bash "$SCRIPT_DIR/gcloud_ssh.sh" "mkdir -- '$REMOTE_DIR'"

# Using gcloud compute scp to copy the bash scripts that will run the benchmarks onto the GCCE
gcloud compute scp \
  "$SCRIPT_DIR/run_pr_benchmarks.sh" \
  "$SCRIPT_DIR/run_main_benchmarks.sh" \
  "$USER@$INSTANCE:$REMOTE_DIR/" \
  --project="$PROJECT_ID" \
  --zone="$ZONE"

# Running the benchmark script for the pull request branch and main branch on GCCE
bash "$SCRIPT_DIR/gcloud_ssh.sh" "export BENCHMARK_DIR='$REMOTE_DIR' PR_HEAD_SHA='$PR_HEAD_SHA' REPO_NAME='$REPO_FULL_NAME' && bash '$REMOTE_DIR/run_pr_benchmarks.sh' && bash '$REMOTE_DIR/run_main_benchmarks.sh'"

# Copying the benchmark results from GCCE back to the Github runner for the PR branch
gcloud compute scp \
  "$USER@$INSTANCE:$REMOTE_DIR/pr/NullAway/jmh/build/results/jmh/results.txt" \
  ./pr_text.txt \
  --project="$PROJECT_ID" \
  --zone="$ZONE"

# Copying the benchmark results from GCCE back to the Github runner for the main branch
gcloud compute scp \
  "$USER@$INSTANCE:$REMOTE_DIR/main/NullAway/jmh/build/results/jmh/results.txt" \
  ./main_text.txt \
  --project="$PROJECT_ID" \
  --zone="$ZONE"
