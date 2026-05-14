#!/usr/bin/env bash
set -euo pipefail

AWS_REGION="${AWS_REGION:-us-east-1}"
WORKER_TAG_KEY="${LB_WORKER_TAG_KEY:-cnv-role}"
WORKER_TAG_VALUE="${LB_WORKER_TAG_VALUE:-worker}"

INSTANCE_IDS="$(aws ec2 describe-instances \
  --region "${AWS_REGION}" \
  --filters "Name=instance-state-name,Values=pending,running,stopping,stopped" "Name=tag:${WORKER_TAG_KEY},Values=${WORKER_TAG_VALUE}" \
  --query "Reservations[].Instances[].InstanceId" \
  --output text)"

if [[ -z "${INSTANCE_IDS}" ]]; then
  echo "No worker instances found."
  exit 0
fi

aws ec2 terminate-instances --region "${AWS_REGION}" --instance-ids ${INSTANCE_IDS}
