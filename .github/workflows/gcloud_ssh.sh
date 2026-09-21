#!/bin/bash

set -euo pipefail

# This script is used to run commands on a Google Cloud instance via SSH

# Define the variables for Google Cloud project, zone, username, and instance
readonly PROJECT_ID="ucr-ursa-major-sridharan-lab"
readonly ZONE="us-central1-a"
readonly USER="root"
readonly INSTANCE="nullway-jmh"

readonly REMOTE_COMMAND="${1:?Remote command is required}"

gcloud compute ssh \
  --project="$PROJECT_ID" \
  --zone="$ZONE" \
  "$USER@$INSTANCE" \
  --command="$REMOTE_COMMAND"
