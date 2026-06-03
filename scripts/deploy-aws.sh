#!/bin/bash
set -e

if [ ! -f "webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar" ]; then
    echo "Error: webserver JAR not found. Run 'mvn clean package' first."
    exit 1
fi

if [ ! -f "javassist/target/javassist-1.0.0-SNAPSHOT-jar-with-dependencies.jar" ]; then
    echo "Error: javassist JAR not found. Run 'mvn clean package' first."
    exit 1
fi

VARS_FILE=".aws-deployment-vars.sh"
echo "#!/bin/bash" > $VARS_FILE
chmod +x $VARS_FILE

echo "Step 1: IAM Roles"
aws iam create-role --role-name CNV-Worker-Role --assume-role-policy-document '{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"Service": "ec2.amazonaws.com"},
    "Action": "sts:AssumeRole"
  }]
}' > /dev/null 2>&1 || true

aws iam attach-role-policy --role-name CNV-Worker-Role --policy-arn arn:aws:iam::aws:policy/AmazonDynamoDBFullAccess 2>/dev/null || true
aws iam create-instance-profile --instance-profile-name CNV-Worker-Profile > /dev/null 2>&1 || true
aws iam add-role-to-instance-profile --instance-profile-name CNV-Worker-Profile --role-name CNV-Worker-Role 2>/dev/null || true

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
aws iam create-role --role-name CNV-LB-Role --assume-role-policy-document '{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"Service": "ec2.amazonaws.com"},
    "Action": "sts:AssumeRole"
  }]
}' > /dev/null 2>&1 || true

aws iam put-role-policy --role-name CNV-LB-Role --policy-name CNV-LB-Policy --policy-document "{
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
      \"Action\": \"cloudwatch:GetMetricStatistics\",
      \"Resource\": \"*\"
    },
    {
      \"Effect\": \"Allow\",
      \"Action\": \"lambda:InvokeFunction\",
      \"Resource\": \"*\"
    },
    {
      \"Effect\": \"Allow\",
      \"Action\": \"iam:PassRole\",
      \"Resource\": \"arn:aws:iam::${ACCOUNT_ID}:role/CNV-Worker-Role\"
    }
  ]
}" 2>/dev/null || true

aws iam create-instance-profile --instance-profile-name CNV-LB-Profile > /dev/null 2>&1 || true
aws iam add-role-to-instance-profile --instance-profile-name CNV-LB-Profile --role-name CNV-LB-Role 2>/dev/null || true

sleep 10

echo "Step 2: Security Groups"
VPC_ID=$(aws ec2 describe-vpcs --filters "Name=isDefault,Values=true" --query "Vpcs[0].VpcId" --output text)
echo "export VPC_ID=$VPC_ID" >> $VARS_FILE

WORKER_SG=$(aws ec2 create-security-group --group-name CNV-Worker-SG --description "CNV workers" --vpc-id $VPC_ID --query 'GroupId' --output text 2>/dev/null || aws ec2 describe-security-groups --filters "Name=group-name,Values=CNV-Worker-SG" --query "SecurityGroups[0].GroupId" --output text)
echo "export WORKER_SG=$WORKER_SG" >> $VARS_FILE
aws ec2 authorize-security-group-ingress --group-id $WORKER_SG --protocol tcp --port 22 --cidr 0.0.0.0/0 2>/dev/null || true
aws ec2 authorize-security-group-ingress --group-id $WORKER_SG --protocol tcp --port 8000 --cidr 0.0.0.0/0 2>/dev/null || true

LB_SG=$(aws ec2 create-security-group --group-name CNV-LB-SG --description "CNV LB" --vpc-id $VPC_ID --query 'GroupId' --output text 2>/dev/null || aws ec2 describe-security-groups --filters "Name=group-name,Values=CNV-LB-SG" --query "SecurityGroups[0].GroupId" --output text)
echo "export LB_SG=$LB_SG" >> $VARS_FILE
aws ec2 authorize-security-group-ingress --group-id $LB_SG --protocol tcp --port 22 --cidr 0.0.0.0/0 2>/dev/null || true
aws ec2 authorize-security-group-ingress --group-id $LB_SG --protocol tcp --port 8000 --cidr 0.0.0.0/0 2>/dev/null || true

echo "Step 3: SSH Key"
if [ ! -f ~/.ssh/cnv-key.pem ]; then
    aws ec2 create-key-pair --key-name cnv-key --query 'KeyMaterial' --output text > ~/.ssh/cnv-key.pem
    chmod 400 ~/.ssh/cnv-key.pem
fi

echo "Step 4: Worker AMI (~10 min)"
AMI_ID=$(aws ec2 describe-images --owners amazon --filters "Name=name,Values=al2023-ami-2023.*-x86_64" "Name=state,Values=available" --query "Images | sort_by(@, &CreationDate) | [-1].ImageId" --output text)

WORKER_INSTANCE=$(aws ec2 run-instances --image-id $AMI_ID --instance-type t3.micro --key-name cnv-key --security-group-ids $WORKER_SG --iam-instance-profile Name=CNV-Worker-Profile --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=CNV-Worker-Builder}]' --query 'Instances[0].InstanceId' --output text)
echo "export WORKER_INSTANCE=$WORKER_INSTANCE" >> $VARS_FILE

aws ec2 wait instance-running --instance-ids $WORKER_INSTANCE
WORKER_IP=$(aws ec2 describe-instances --instance-ids $WORKER_INSTANCE --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
sleep 30

scp -i ~/.ssh/cnv-key.pem -o StrictHostKeyChecking=no webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar ec2-user@$WORKER_IP:/home/ec2-user/webserver.jar > /dev/null
scp -i ~/.ssh/cnv-key.pem javassist/target/javassist-1.0.0-SNAPSHOT-jar-with-dependencies.jar ec2-user@$WORKER_IP:/home/ec2-user/javassist.jar > /dev/null

ssh -i ~/.ssh/cnv-key.pem ec2-user@$WORKER_IP << 'ENDSSH'
sudo yum install -y java-17-amazon-corretto > /dev/null 2>&1

sudo tee /etc/systemd/system/cnv-worker.service > /dev/null <<'EOF'
[Unit]
Description=CNV Worker Service
After=network.target

[Service]
Type=simple
User=ec2-user
WorkingDirectory=/home/ec2-user
Environment="WORKER_PORT=8000"
ExecStart=/usr/bin/java -javaagent:/home/ec2-user/javassist.jar=MetricsTool:pt.ulisboa.tecnico.cnv.fractals,pt.ulisboa.tecnico.cnv.grayscott,pt.ulisboa.tecnico.cnv.dna:output -cp /home/ec2-user/webserver.jar pt.ulisboa.tecnico.cnv.webserver.WorkerWebServer
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable cnv-worker.service
sudo systemctl start cnv-worker.service
ENDSSH

aws ec2 stop-instances --instance-ids $WORKER_INSTANCE > /dev/null
aws ec2 wait instance-stopped --instance-ids $WORKER_INSTANCE

WORKER_AMI=$(aws ec2 create-image --instance-id $WORKER_INSTANCE --name "CNV-Worker-AMI-$(date +%Y%m%d-%H%M%S)" --description "CNV Worker" --query 'ImageId' --output text)
echo "export WORKER_AMI=$WORKER_AMI" >> $VARS_FILE
echo "AMI: $WORKER_AMI"

aws ec2 wait image-available --image-ids $WORKER_AMI
aws ec2 terminate-instances --instance-ids $WORKER_INSTANCE > /dev/null

echo "Step 5: Launch Template"
LAUNCH_TEMPLATE_ID=$(aws ec2 create-launch-template --launch-template-name CNV-Worker-Template --launch-template-data "{
  \"ImageId\": \"$WORKER_AMI\",
  \"InstanceType\": \"t3.micro\",
  \"KeyName\": \"cnv-key\",
  \"SecurityGroupIds\": [\"$WORKER_SG\"],
  \"IamInstanceProfile\": {\"Name\": \"CNV-Worker-Profile\"},
  \"TagSpecifications\": [{\"ResourceType\": \"instance\",\"Tags\": [{\"Key\": \"cnv-role\",\"Value\": \"worker\"}]}]
}" --query 'LaunchTemplate.LaunchTemplateId' --output text)
echo "export LAUNCH_TEMPLATE_ID=$LAUNCH_TEMPLATE_ID" >> $VARS_FILE

echo "Step 6: Load Balancer"
LB_INSTANCE=$(aws ec2 run-instances --image-id $AMI_ID --instance-type t3.small --key-name cnv-key --security-group-ids $LB_SG --iam-instance-profile Name=CNV-LB-Profile --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=CNV-LoadBalancer}]' --query 'Instances[0].InstanceId' --output text)
echo "export LB_INSTANCE=$LB_INSTANCE" >> $VARS_FILE

aws ec2 wait instance-running --instance-ids $LB_INSTANCE
LB_IP=$(aws ec2 describe-instances --instance-ids $LB_INSTANCE --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
echo "export LB_IP=$LB_IP" >> $VARS_FILE
sleep 30

scp -i ~/.ssh/cnv-key.pem -o StrictHostKeyChecking=no webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar ec2-user@$LB_IP:/home/ec2-user/webserver.jar > /dev/null

ssh -i ~/.ssh/cnv-key.pem ec2-user@$LB_IP << ENDCONFIG
sudo yum install -y java-17-amazon-corretto > /dev/null 2>&1

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
LB_FORWARD_TIMEOUT_MS=600000
LB_LAMBDA_ENABLED=true
LB_LAMBDA_COMPLEXITY_THRESHOLD=20000000000
LB_LAMBDA_FUNCTION_FRACTALS=cnv-fractals
LB_LAMBDA_FUNCTION_DNA=cnv-dna
LB_LAMBDA_FUNCTION_GRAYSCOTT=cnv-grayscott
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

echo "Step 7: Initial Workers"
aws ec2 run-instances --launch-template LaunchTemplateId=$LAUNCH_TEMPLATE_ID --count 2 --monitoring Enabled=true > /dev/null
echo "Waiting 3 minutes for workers to boot..."
sleep 180
ssh -i ~/.ssh/cnv-key.pem ec2-user@$LB_IP "sudo systemctl restart cnv-lb.service" 2>/dev/null

echo "Polling LB until healthy workers are registered..."
attempts=0
until [ $attempts -ge 36 ] || curl -sf "http://$LB_IP:8000/test" > /dev/null 2>&1; do
    attempts=$((attempts + 1))
    echo "  Not ready yet ($attempts/36), retrying in 10s..."
    sleep 10
done

if curl -sf "http://$LB_IP:8000/test" > /dev/null 2>&1; then
    echo "LB is ready."
else
    echo "Warning: LB did not become healthy within 6 minutes. Check worker boot logs."
fi

echo ""
echo "Deployment complete!"
echo "LB IP: $LB_IP"
echo "Test: curl http://$LB_IP:8000/"
