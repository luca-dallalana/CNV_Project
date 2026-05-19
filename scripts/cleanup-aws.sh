#!/bin/bash
set -e

VARS_FILE=".aws-deployment-vars.sh"
if [ -f "$VARS_FILE" ]; then
    source $VARS_FILE
fi

read -p "Delete ALL AWS resources? (yes/no): " confirm
if [ "$confirm" != "yes" ]; then
    exit 0
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

sleep 120

if [ -n "$LAUNCH_TEMPLATE_ID" ]; then
    aws ec2 delete-launch-template --launch-template-id $LAUNCH_TEMPLATE_ID > /dev/null 2>&1 || true
else
    TEMPLATE_ID=$(aws ec2 describe-launch-templates --filters "Name=launch-template-name,Values=CNV-Worker-Template" --query 'LaunchTemplates[0].LaunchTemplateId' --output text 2>/dev/null)
    if [ "$TEMPLATE_ID" != "None" ] && [ -n "$TEMPLATE_ID" ]; then
        aws ec2 delete-launch-template --launch-template-id $TEMPLATE_ID > /dev/null
    fi
fi

if [ -n "$WORKER_AMI" ]; then
    aws ec2 deregister-image --image-id $WORKER_AMI > /dev/null 2>&1 || true
else
    AMI_ID=$(aws ec2 describe-images --owners self --filters "Name=name,Values=CNV-Worker-AMI-*" --query 'Images[0].ImageId' --output text 2>/dev/null)
    if [ "$AMI_ID" != "None" ] && [ -n "$AMI_ID" ]; then
        aws ec2 deregister-image --image-id $AMI_ID > /dev/null
    fi
fi

sleep 30

if [ -n "$WORKER_SG" ]; then
    aws ec2 delete-security-group --group-id $WORKER_SG > /dev/null 2>&1 || true
else
    SG_ID=$(aws ec2 describe-security-groups --filters "Name=group-name,Values=CNV-Worker-SG" --query 'SecurityGroups[0].GroupId' --output text 2>/dev/null)
    if [ "$SG_ID" != "None" ] && [ -n "$SG_ID" ]; then
        aws ec2 delete-security-group --group-id $SG_ID > /dev/null 2>&1 || true
    fi
fi

if [ -n "$LB_SG" ]; then
    aws ec2 delete-security-group --group-id $LB_SG > /dev/null 2>&1 || true
else
    SG_ID=$(aws ec2 describe-security-groups --filters "Name=group-name,Values=CNV-LB-SG" --query 'SecurityGroups[0].GroupId' --output text 2>/dev/null)
    if [ "$SG_ID" != "None" ] && [ -n "$SG_ID" ]; then
        aws ec2 delete-security-group --group-id $SG_ID > /dev/null 2>&1 || true
    fi
fi

aws iam remove-role-from-instance-profile --instance-profile-name CNV-Worker-Profile --role-name CNV-Worker-Role > /dev/null 2>&1 || true
aws iam delete-instance-profile --instance-profile-name CNV-Worker-Profile > /dev/null 2>&1 || true
aws iam detach-role-policy --role-name CNV-Worker-Role --policy-arn arn:aws:iam::aws:policy/AmazonDynamoDBFullAccess > /dev/null 2>&1 || true
aws iam delete-role --role-name CNV-Worker-Role > /dev/null 2>&1 || true

aws iam remove-role-from-instance-profile --instance-profile-name CNV-LB-Profile --role-name CNV-LB-Role > /dev/null 2>&1 || true
aws iam delete-instance-profile --instance-profile-name CNV-LB-Profile > /dev/null 2>&1 || true
aws iam delete-role-policy --role-name CNV-LB-Role --policy-name CNV-LB-Policy > /dev/null 2>&1 || true
aws iam delete-role --role-name CNV-LB-Role > /dev/null 2>&1 || true

read -p "Delete DynamoDB table 'cnv-metrics'? (yes/no): " delete_db
if [ "$delete_db" = "yes" ]; then
    aws dynamodb delete-table --table-name cnv-metrics > /dev/null 2>&1 || true
fi

read -p "Delete SSH key pair 'cnv-key'? (yes/no): " delete_key
if [ "$delete_key" = "yes" ]; then
    aws ec2 delete-key-pair --key-name cnv-key > /dev/null 2>&1 || true
    if [ -f ~/.ssh/cnv-key.pem ]; then
        rm ~/.ssh/cnv-key.pem
    fi
fi

if [ -f "$VARS_FILE" ]; then
    rm $VARS_FILE
fi
