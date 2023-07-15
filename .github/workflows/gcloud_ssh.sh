#!/bin/bash

# This script is used to run commands on a Google Cloud instance via SSH

# Define the variables for Google Cloud project, zone, username, and instance
PROJECT_ID="ucr-ursa-major-sridharan-lab"
ZONE="us-central1-a"
USER="root"
INSTANCE="nullway-jmh"

gcloud compute ssh --project=$PROJECT_ID --zone=$ZONE $USER@$INSTANCE --command="$1"
