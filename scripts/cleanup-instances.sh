#!/bin/bash
set -e

VARS_FILE=".aws-deployment-vars.sh"
if [ -f "$VARS_FILE" ]; then
    source $VARS_FILE
fi

WORKER_IDS=$(aws ec2 describe-instances \
  --filters "Name=tag:cnv-role,Values=worker" "Name=instance-state-name,Values=running,pending,stopping,stopped" \
  --query 'Reservations[*].Instances[*].InstanceId' \
  --output text)

if [ -n "$WORKER_IDS" ]; then
    aws ec2 terminate-instances --instance-ids $WORKER_IDS > /dev/null
fi

if [ -n "$LB_INSTANCE" ]; then
    aws ec2 terminate-instances --instance-ids $LB_INSTANCE > /dev/null 2>&1 || true
else
    LB_ID=$(aws ec2 describe-instances \
      --filters "Name=tag:Name,Values=CNV-LoadBalancer" "Name=instance-state-name,Values=running,pending,stopping,stopped" \
      --query 'Reservations[0].Instances[0].InstanceId' \
      --output text)
    if [ "$LB_ID" != "None" ] && [ -n "$LB_ID" ]; then
        aws ec2 terminate-instances --instance-ids $LB_ID > /dev/null
    fi
fi
