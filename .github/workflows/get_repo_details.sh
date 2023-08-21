#!/bin/bash

# This script retrieves the repository and branch details of a GitHub pull request

# Assign command line arguments to variables
# GH_TOKEN is the GitHub authentication token
# PR_NUMBER is the number of the pull request
# REPO_NAME is the name of the repository
GH_TOKEN="$1"
PR_NUMBER="$2"
REPO_NAME="$3"

PR_DETAILS=$(curl -s -H "Authorization: token $GH_TOKEN" "https://api.github.com/repos/$REPO_NAME/pulls/$PR_NUMBER")

REPO_FULL_NAME=$(echo "$PR_DETAILS" | jq -r .head.repo.full_name)
BRANCH_NAME=$(echo "$PR_DETAILS" | jq -r .head.ref)

# Export vars to GITHUB_ENV so they can be used by later scripts
echo "REPO_FULL_NAME=$REPO_FULL_NAME" >> "$GITHUB_ENV"
echo "BRANCH_NAME=$BRANCH_NAME" >> "$GITHUB_ENV"
