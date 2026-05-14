#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <desired-count>"
  echo "Required env: LB_WORKER_LAUNCH_TEMPLATE_ID"
  echo "Optional env: AWS_REGION, LB_WORKER_LAUNCH_TEMPLATE_VERSION"
  exit 1
fi

DESIRED_COUNT="$1"
LAUNCH_TEMPLATE_ID="${LB_WORKER_LAUNCH_TEMPLATE_ID:-}"
LAUNCH_TEMPLATE_VERSION="${LB_WORKER_LAUNCH_TEMPLATE_VERSION:-\$Default}"
AWS_REGION="${AWS_REGION:-us-east-1}"

if [[ -z "${LAUNCH_TEMPLATE_ID}" ]]; then
  echo "Missing LB_WORKER_LAUNCH_TEMPLATE_ID."
  exit 1
fi

aws ec2 run-instances \
  --region "${AWS_REGION}" \
  --launch-template "LaunchTemplateId=${LAUNCH_TEMPLATE_ID},Version=${LAUNCH_TEMPLATE_VERSION}" \
  --min-count "${DESIRED_COUNT}" \
  --max-count "${DESIRED_COUNT}"
