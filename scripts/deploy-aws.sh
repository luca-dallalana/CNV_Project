#!/bin/bash
set -e

echo "=========================================="
echo "CNV Nature@Cloud AWS Deployment Script"
echo "=========================================="
echo ""

# Check if JARs are built
if [ ! -f "webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar" ]; then
    echo "Error: webserver JAR not found. Run 'mvn clean package' first."
    exit 1
fi

if [ ! -f "javassist/target/javassist-1.0.0-SNAPSHOT-jar-with-dependencies.jar" ]; then
    echo "Error: javassist JAR not found. Run 'mvn clean package' first."
    exit 1
fi

# Store variables in a file for later use
VARS_FILE=".aws-deployment-vars.sh"
echo "#!/bin/bash" > $VARS_FILE
chmod +x $VARS_FILE

echo "Step 1: Creating IAM Roles..."
echo "=============================="

# Worker Role
aws iam create-role \
  --role-name CNV-Worker-Role \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"Service": "ec2.amazonaws.com"},
      "Action": "sts:AssumeRole"
    }]
  }' > /dev/null 2>&1 || echo "Worker role already exists"

aws iam attach-role-policy \
  --role-name CNV-Worker-Role \
  --policy-arn arn:aws:iam::aws:policy/AmazonDynamoDBFullAccess

aws iam create-instance-profile \
  --instance-profile-name CNV-Worker-Profile > /dev/null 2>&1 || echo "Worker profile already exists"

aws iam add-role-to-instance-profile \
  --instance-profile-name CNV-Worker-Profile \
  --role-name CNV-Worker-Role 2>/dev/null || true

# LB Role
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)

aws iam create-role \
  --role-name CNV-LB-Role \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"Service": "ec2.amazonaws.com"},
      "Action": "sts:AssumeRole"
    }]
  }' > /dev/null 2>&1 || echo "LB role already exists"

aws iam put-role-policy \
  --role-name CNV-LB-Role \
  --policy-name CNV-LB-Policy \
  --policy-document "{
    \"Version\": \"2012-10-17\",
    \"Statement\": [
      {
        \"Effect\": \"Allow\",
        \"Action\": [
          \"ec2:DescribeInstances\",
          \"ec2:RunInstances\",
          \"ec2:TerminateInstances\",
          \"ec2:CreateTags\"
        ],
        \"Resource\": \"*\"
      },
      {
        \"Effect\": \"Allow\",
        \"Action\": \"dynamodb:*\",
        \"Resource\": \"*\"
      },
      {
        \"Effect\": \"Allow\",
        \"Action\": \"iam:PassRole\",
        \"Resource\": \"arn:aws:iam::${ACCOUNT_ID}:role/CNV-Worker-Role\"
      }
    ]
  }"

aws iam create-instance-profile \
  --instance-profile-name CNV-LB-Profile > /dev/null 2>&1 || echo "LB profile already exists"

aws iam add-role-to-instance-profile \
  --instance-profile-name CNV-LB-Profile \
  --role-name CNV-LB-Role 2>/dev/null || true

echo "✓ IAM roles created"
echo ""

# Wait for IAM propagation
echo "Waiting 10s for IAM propagation..."
sleep 10

echo "Step 2: Creating Security Groups..."
echo "===================================="

VPC_ID=$(aws ec2 describe-vpcs --filters "Name=isDefault,Values=true" --query "Vpcs[0].VpcId" --output text)
echo "export VPC_ID=$VPC_ID" >> $VARS_FILE

# Worker SG
WORKER_SG=$(aws ec2 create-security-group \
  --group-name CNV-Worker-SG \
  --description "Security group for CNV workers" \
  --vpc-id $VPC_ID \
  --query 'GroupId' \
  --output text 2>/dev/null || aws ec2 describe-security-groups --filters "Name=group-name,Values=CNV-Worker-SG" --query "SecurityGroups[0].GroupId" --output text)

echo "export WORKER_SG=$WORKER_SG" >> $VARS_FILE

aws ec2 authorize-security-group-ingress --group-id $WORKER_SG --protocol tcp --port 22 --cidr 0.0.0.0/0 2>/dev/null || true
aws ec2 authorize-security-group-ingress --group-id $WORKER_SG --protocol tcp --port 8000 --cidr 0.0.0.0/0 2>/dev/null || true

# LB SG
LB_SG=$(aws ec2 create-security-group \
  --group-name CNV-LB-SG \
  --description "Security group for CNV load balancer" \
  --vpc-id $VPC_ID \
  --query 'GroupId' \
  --output text 2>/dev/null || aws ec2 describe-security-groups --filters "Name=group-name,Values=CNV-LB-SG" --query "SecurityGroups[0].GroupId" --output text)

echo "export LB_SG=$LB_SG" >> $VARS_FILE

aws ec2 authorize-security-group-ingress --group-id $LB_SG --protocol tcp --port 22 --cidr 0.0.0.0/0 2>/dev/null || true
aws ec2 authorize-security-group-ingress --group-id $LB_SG --protocol tcp --port 8000 --cidr 0.0.0.0/0 2>/dev/null || true

echo "✓ Security groups created"
echo "  Worker SG: $WORKER_SG"
echo "  LB SG: $LB_SG"
echo ""

echo "Step 3: Creating Key Pair..."
echo "============================="

if [ ! -f ~/.ssh/cnv-key.pem ]; then
    aws ec2 create-key-pair \
      --key-name cnv-key \
      --query 'KeyMaterial' \
      --output text > ~/.ssh/cnv-key.pem
    chmod 400 ~/.ssh/cnv-key.pem
    echo "✓ Key pair created at ~/.ssh/cnv-key.pem"
else
    echo "✓ Key pair already exists"
fi
echo ""

echo "Step 4: Creating Worker AMI..."
echo "==============================="

AMI_ID=$(aws ec2 describe-images \
  --owners amazon \
  --filters "Name=name,Values=al2023-ami-2023.*-x86_64" "Name=state,Values=available" \
  --query "Images | sort_by(@, &CreationDate) | [-1].ImageId" \
  --output text)

echo "Base AMI: $AMI_ID"

# Launch builder instance
echo "Launching builder instance..."
WORKER_INSTANCE=$(aws ec2 run-instances \
  --image-id $AMI_ID \
  --instance-type t3.micro \
  --key-name cnv-key \
  --security-group-ids $WORKER_SG \
  --iam-instance-profile Name=CNV-Worker-Profile \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=CNV-Worker-Builder}]' \
  --query 'Instances[0].InstanceId' \
  --output text)

echo "export WORKER_INSTANCE=$WORKER_INSTANCE" >> $VARS_FILE
echo "Worker instance: $WORKER_INSTANCE"

aws ec2 wait instance-running --instance-ids $WORKER_INSTANCE

WORKER_IP=$(aws ec2 describe-instances \
  --instance-ids $WORKER_INSTANCE \
  --query 'Reservations[0].Instances[0].PublicIpAddress' \
  --output text)

echo "Worker IP: $WORKER_IP"
echo "Waiting 30s for SSH..."
sleep 30

# Copy JARs
echo "Copying JARs to worker..."
scp -i ~/.ssh/cnv-key.pem -o StrictHostKeyChecking=no \
  webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  ec2-user@$WORKER_IP:/home/ec2-user/webserver.jar

scp -i ~/.ssh/cnv-key.pem \
  javassist/target/javassist-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  ec2-user@$WORKER_IP:/home/ec2-user/javassist.jar

# Configure worker
echo "Configuring worker..."
ssh -i ~/.ssh/cnv-key.pem ec2-user@$WORKER_IP << 'ENDSSH'
sudo yum install -y java-17-amazon-corretto

sudo tee /etc/systemd/system/cnv-worker.service > /dev/null <<'EOF'
[Unit]
Description=CNV Worker Service
After=network.target

[Service]
Type=simple
User=ec2-user
WorkingDirectory=/home/ec2-user
Environment="WORKER_PORT=8000"
ExecStart=/usr/bin/java -cp /home/ec2-user/webserver.jar -Xbootclasspath/a:/home/ec2-user/javassist.jar -javaagent:/home/ec2-user/webserver.jar=MetricsTool:pt.ulisboa.tecnico.cnv.fractals,pt.ulisboa.tecnico.cnv.grayscott,pt.ulisboa.tecnico.cnv.dna:output pt.ulisboa.tecnico.cnv.webserver.WorkerWebServer
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable cnv-worker.service
sudo systemctl start cnv-worker.service
sleep 5
sudo systemctl status cnv-worker.service --no-pager
ENDSSH

echo "✓ Worker configured"

# Create AMI
echo "Creating AMI (this takes 5-10 minutes)..."
aws ec2 stop-instances --instance-ids $WORKER_INSTANCE
aws ec2 wait instance-stopped --instance-ids $WORKER_INSTANCE

WORKER_AMI=$(aws ec2 create-image \
  --instance-id $WORKER_INSTANCE \
  --name "CNV-Worker-AMI-$(date +%Y%m%d-%H%M%S)" \
  --description "CNV Worker with auto-start" \
  --query 'ImageId' \
  --output text)

echo "export WORKER_AMI=$WORKER_AMI" >> $VARS_FILE
echo "Worker AMI: $WORKER_AMI"
echo "Waiting for AMI..."
aws ec2 wait image-available --image-ids $WORKER_AMI

aws ec2 terminate-instances --instance-ids $WORKER_INSTANCE
echo "✓ AMI created: $WORKER_AMI"
echo ""

echo "Step 5: Creating Launch Template..."
echo "===================================="

LAUNCH_TEMPLATE_ID=$(aws ec2 create-launch-template \
  --launch-template-name CNV-Worker-Template \
  --launch-template-data "{
    \"ImageId\": \"$WORKER_AMI\",
    \"InstanceType\": \"t3.micro\",
    \"KeyName\": \"cnv-key\",
    \"SecurityGroupIds\": [\"$WORKER_SG\"],
    \"IamInstanceProfile\": {
      \"Name\": \"CNV-Worker-Profile\"
    },
    \"TagSpecifications\": [{
      \"ResourceType\": \"instance\",
      \"Tags\": [{
        \"Key\": \"cnv-role\",
        \"Value\": \"worker\"
      }]
    }]
  }" \
  --query 'LaunchTemplate.LaunchTemplateId' \
  --output text)

echo "export LAUNCH_TEMPLATE_ID=$LAUNCH_TEMPLATE_ID" >> $VARS_FILE
echo "✓ Launch Template: $LAUNCH_TEMPLATE_ID"
echo ""

echo "Step 6: Deploying Load Balancer..."
echo "===================================="

LB_INSTANCE=$(aws ec2 run-instances \
  --image-id $AMI_ID \
  --instance-type t3.small \
  --key-name cnv-key \
  --security-group-ids $LB_SG \
  --iam-instance-profile Name=CNV-LB-Profile \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=CNV-LoadBalancer}]' \
  --query 'Instances[0].InstanceId' \
  --output text)

echo "export LB_INSTANCE=$LB_INSTANCE" >> $VARS_FILE
echo "LB instance: $LB_INSTANCE"

aws ec2 wait instance-running --instance-ids $LB_INSTANCE

LB_IP=$(aws ec2 describe-instances \
  --instance-ids $LB_INSTANCE \
  --query 'Reservations[0].Instances[0].PublicIpAddress' \
  --output text)

echo "export LB_IP=$LB_IP" >> $VARS_FILE
echo "LB IP: $LB_IP"
echo "Waiting 30s for SSH..."
sleep 30

# Copy JAR
scp -i ~/.ssh/cnv-key.pem -o StrictHostKeyChecking=no \
  webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  ec2-user@$LB_IP:/home/ec2-user/webserver.jar

# Configure LB
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
sleep 10
sudo systemctl status cnv-lb.service --no-pager
ENDCONFIG

echo "✓ Load Balancer deployed"
echo ""

echo "Step 7: Launching Initial Workers..."
echo "======================================"

aws ec2 run-instances \
  --launch-template LaunchTemplateId=$LAUNCH_TEMPLATE_ID \
  --count 2

echo "Launched 2 workers. Waiting 60s..."
sleep 60

# Restart LB to discover workers
ssh -i ~/.ssh/cnv-key.pem ec2-user@$LB_IP "sudo systemctl restart cnv-lb.service"
sleep 15

echo "✓ Workers launched"
echo ""

echo "=========================================="
echo "✓ DEPLOYMENT COMPLETE!"
echo "=========================================="
echo ""
echo "Load Balancer Public IP: $LB_IP"
echo ""
echo "Test with:"
echo "  curl http://$LB_IP:8000/"
echo "  curl \"http://$LB_IP:8000/fractals?w=200&h=200&iterations=50\""
echo ""
echo "Deployment variables saved to: $VARS_FILE"
echo ""
echo "To cleanup, run: ./scripts/cleanup-aws.sh"
echo ""
