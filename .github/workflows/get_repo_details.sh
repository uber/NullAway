#!/bin/bash

# This script retrieves the repository and branch details of a GitHub pull request

# Assign command line arguments to variables
# GH_TOKEN is the GitHub authentication token
# PR_NUMBER is the number of the pull request
# REPO_NAME is the name of the repository
GH_TOKEN="$1"
PR_NUMBER="$2"
REPO_NAME="$3"

# Use a curl command to retrieve the details of the pull request from the GitHub API
# The GH_TOKEN is used for authentication
# The output of the curl command is stored in the PR_DETAILS variable
PR_DETAILS=$(curl -s -H "Authorization: token $GH_TOKEN" "https://api.github.com/repos/$REPO_NAME/pulls/$PR_NUMBER")

# Use the jq command to parse the JSON output of the curl command
# The full name of the repository and the branch name are extracted from the JSON and stored in the REPO_FULL_NAME and BRANCH_NAME variables respectively
REPO_FULL_NAME=$(echo "$PR_DETAILS" | jq -r .head.repo.full_name)
BRANCH_NAME=$(echo "$PR_DETAILS" | jq -r .head.ref)

# Export the REPO_FULL_NAME and BRANCH_NAME variables
# The variables are appended to the $GITHUB_ENV file, making them available to subsequent steps in a GitHub Actions workflow
echo "REPO_FULL_NAME=$REPO_FULL_NAME" >> $GITHUB_ENV
echo "BRANCH_NAME=$BRANCH_NAME" >> $GITHUB_ENV
