#!/bin/bash

# This script is used to run commands on a Google Cloud instance via SSH

# Define the variables for Google Cloud project, zone, username, and instance
PROJECT_ID="nullway-jmh"
ZONE="us-central1-a"
USER="root"
INSTANCE="instance-1"

# Use the gcloud compute ssh command to run a command ($1) on the specified Google Cloud instance
# $1 is a positional parameter that represents the first argument given when calling this script
gcloud compute ssh --project=$PROJECT_ID --zone=$ZONE $USER@$INSTANCE --command="$1"
