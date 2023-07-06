#!/bin/bash

# Assign command line argument to a variable
GH_TOKEN="$1"
PR_NUMBER="$2"
REPO_NAME="$3"

# Get PR details
PR_DETAILS=$(curl -s -H "Authorization: token $GH_TOKEN" "https://api.github.com/repos/$REPO_NAME/pulls/$PR_NUMBER")

# Get repo full name and branch name
REPO_FULL_NAME=$(echo "$PR_DETAILS" | jq -r .head.repo.full_name)
BRANCH_NAME=$(echo "$PR_DETAILS" | jq -r .head.ref)

# Export the variables
echo "REPO_FULL_NAME=$REPO_FULL_NAME" >> $GITHUB_ENV
echo "BRANCH_NAME=$BRANCH_NAME" >> $GITHUB_ENV
