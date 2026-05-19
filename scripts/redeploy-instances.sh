#!/bin/bash
set -e

VARS_FILE=".aws-deployment-vars.sh"
if [ ! -f "$VARS_FILE" ]; then
    exit 1
fi

source $VARS_FILE

AMI_ID=$(aws ec2 describe-images \
  --owners amazon \
  --filters "Name=name,Values=al2023-ami-2023.*-x86_64" "Name=state,Values=available" \
  --query "Images | sort_by(@, &CreationDate) | [-1].ImageId" \
  --output text)

LB_INSTANCE=$(aws ec2 run-instances \
  --image-id $AMI_ID \
  --instance-type t3.small \
  --key-name cnv-key \
  --security-group-ids $LB_SG \
  --iam-instance-profile Name=CNV-LB-Profile \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=CNV-LoadBalancer}]' \
  --query 'Instances[0].InstanceId' \
  --output text)

sed -i.bak "s/export LB_INSTANCE=.*/export LB_INSTANCE=$LB_INSTANCE/" $VARS_FILE

aws ec2 wait instance-running --instance-ids $LB_INSTANCE

LB_IP=$(aws ec2 describe-instances \
  --instance-ids $LB_INSTANCE \
  --query 'Reservations[0].Instances[0].PublicIpAddress' \
  --output text)

sed -i.bak "s/export LB_IP=.*/export LB_IP=$LB_IP/" $VARS_FILE

sleep 30

scp -i ~/.ssh/cnv-key.pem -o StrictHostKeyChecking=no \
  webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  ec2-user@$LB_IP:/home/ec2-user/webserver.jar

ssh -i ~/.ssh/cnv-key.pem ec2-user@$LB_IP << ENDCONFIG
sudo yum install -y java-17-amazon-corretto

cat > /home/ec2-user/lb-env.conf <<EOF
AWS_REGION=us-east-1
CNV_METRICS_TABLE=cnv-metrics
LB_PORT=8000
LB_WORKER_PORT=8000
LB_WORKER_TAG_KEY=cnv-role
LB_WORKER_TAG_VALUE=worker
LB_WORKER_LAUNCH_TEMPLATE_ID=$LAUNCH_TEMPLATE_ID
LB_WORKER_LAUNCH_TEMPLATE_VERSION=\\\$Default
LB_MIN_WORKERS=1
LB_MAX_WORKERS=6
LB_SCALE_OUT_PRESSURE=30000000
LB_SCALE_IN_PRESSURE=8000000
LB_SCALER_PERIOD_MS=10000
LB_SCALER_COOLDOWN_MS=60000
EOF

sudo tee /etc/systemd/system/cnv-lb.service > /dev/null <<'EOF'
[Unit]
Description=CNV Load Balancer Service
After=network.target

[Service]
Type=simple
User=ec2-user
WorkingDirectory=/home/ec2-user
EnvironmentFile=/home/ec2-user/lb-env.conf
ExecStart=/usr/bin/java -cp /home/ec2-user/webserver.jar pt.ulisboa.tecnico.cnv.webserver.WebServer
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable cnv-lb.service
sudo systemctl start cnv-lb.service
ENDCONFIG

aws ec2 run-instances \
  --launch-template LaunchTemplateId=$LAUNCH_TEMPLATE_ID \
  --count 2 > /dev/null

sleep 60

ssh -i ~/.ssh/cnv-key.pem ec2-user@$LB_IP "sudo systemctl restart cnv-lb.service"
sleep 15

echo "LB IP: $LB_IP"
