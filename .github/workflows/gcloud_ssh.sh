#!/bin/bash

PROJECT_ID="nullway-jmh"
ZONE="us-central1-a"
USER="root"
INSTANCE="instance-1"

gcloud compute ssh --project=$PROJECT_ID --zone=$ZONE $USER@$INSTANCE --command="$1"
