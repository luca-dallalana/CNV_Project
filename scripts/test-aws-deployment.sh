#!/bin/bash
set -e

VARS_FILE=".aws-deployment-vars.sh"
if [ ! -f "$VARS_FILE" ]; then
    exit 1
fi

source $VARS_FILE

RESPONSE=$(curl -s --max-time 5 "http://$LB_IP:8000/" || echo "FAILED")
if [[ "$RESPONSE" != *"Nature@Cloud"* ]]; then
    exit 1
fi

RESPONSE=$(curl -s --max-time 15 "http://$LB_IP:8000/fractals?w=200&h=200&iterations=50")
if [[ "$RESPONSE" != *"image"* ]]; then
    exit 1
fi

sleep 5

METRICS=$(aws dynamodb scan --table-name cnv-metrics --limit 1 --query 'Count' --output text 2>/dev/null)
if [ "$METRICS" -le 0 ]; then
    exit 1
fi

curl -s --max-time 15 "http://$LB_IP:8000/fractals?w=200&h=200&iterations=50" > /dev/null

ssh -i ~/.ssh/cnv-key.pem ec2-user@$LB_IP "sudo journalctl -u cnv-lb.service | grep 'Request: workload=fractals' | tail -2" > /tmp/cnv-test-logs.txt

FIRST_COMPLEXITY=$(grep -o 'complexity=[0-9]*' /tmp/cnv-test-logs.txt | head -1 | cut -d= -f2)
SECOND_COMPLEXITY=$(grep -o 'complexity=[0-9]*' /tmp/cnv-test-logs.txt | tail -1 | cut -d= -f2)

WORKERS=$(aws ec2 describe-instances \
  --filters "Name=tag:cnv-role,Values=worker" "Name=instance-state-name,Values=running" \
  --query 'Reservations[*].Instances[*].InstanceId' \
  --output text | wc -w | tr -d ' ')

if [ "$WORKERS" -lt 1 ]; then
    exit 1
fi

for i in {1..10}; do
    curl -s "http://$LB_IP:8000/fractals?w=800&h=600&iterations=500" > /dev/null &
done

sleep 30

AS_LOGS=$(ssh -i ~/.ssh/cnv-key.pem ec2-user@$LB_IP "sudo journalctl -u cnv-lb.service --since '2 minutes ago' | grep '\[AS\]'" || echo "")

WORKERS_AFTER=$(aws ec2 describe-instances \
  --filters "Name=tag:cnv-role,Values=worker" "Name=instance-state-name,Values=running,pending" \
  --query 'Reservations[*].Instances[*].InstanceId' \
  --output text | wc -w | tr -d ' ')

LB_LOGS=$(ssh -i ~/.ssh/cnv-key.pem ec2-user@$LB_IP "sudo journalctl -u cnv-lb.service | grep 'Selected worker' | tail -5")
UNIQUE_WORKERS=$(echo "$LB_LOGS" | grep -o 'i-[a-f0-9]*' | sort -u | wc -l | tr -d ' ')

if [ "$UNIQUE_WORKERS" -lt 1 ]; then
    exit 1
fi

echo "LB IP: $LB_IP"
echo "Workers: $WORKERS_AFTER"
echo "DynamoDB Records: $METRICS"
echo "1st complexity: $FIRST_COMPLEXITY (heuristic)"
echo "2nd complexity: $SECOND_COMPLEXITY (historical)"
