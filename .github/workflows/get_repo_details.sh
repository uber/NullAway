#!/bin/bash

set -euo pipefail

# This script retrieves the head repository and commit of a GitHub pull request

# Assign command line arguments to variables
# GH_TOKEN is the GitHub authentication token
# PR_NUMBER is the number of the pull request
# REPO_NAME is the name of the repository
GH_TOKEN="${1:?GitHub token is required}"
PR_NUMBER="${2:?Pull request number is required}"
REPO_NAME="${3:?Repository name is required}"

PR_DETAILS=$(curl --fail-with-body --silent --show-error \
  -H "Authorization: token $GH_TOKEN" \
  "https://api.github.com/repos/$REPO_NAME/pulls/$PR_NUMBER")

REPO_FULL_NAME=$(jq -er '.head.repo.full_name | select(type == "string" and length > 0)' <<< "$PR_DETAILS")
PR_HEAD_SHA=$(jq -er '.head.sha | select(type == "string" and length > 0)' <<< "$PR_DETAILS")

if [[ ! "$REPO_FULL_NAME" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
  echo "Invalid head repository name: $REPO_FULL_NAME" >&2
  exit 1
fi
if [[ ! "$PR_HEAD_SHA" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "Invalid pull request head SHA: $PR_HEAD_SHA" >&2
  exit 1
fi

# Export vars to GITHUB_ENV so they can be used by later scripts
echo "REPO_FULL_NAME=$REPO_FULL_NAME" >> "$GITHUB_ENV"
echo "PR_HEAD_SHA=$PR_HEAD_SHA" >> "$GITHUB_ENV"
