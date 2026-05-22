# CNV Project Checkpoint - Group 09

## Architecture Overview

### System Components

1. **Load Balancer (LB)**
   - Instance Type: `t3.small`
   - Runs on EC2 with IAM role for EC2 and DynamoDB access
   - Discovers workers via EC2 tags (`cnv-role=worker`)
   - Implements complexity-based request routing
   - Manages autoscaling based on cluster pressure

2. **Worker Instances**
   - Instance Type: `t3.micro`
   - Created from custom AMI with instrumented workloads
   - Run Nature@Cloud workloads (fractals, grayscott, dna)
   - Instrumented with Javassist-based metrics collection
   - Store metrics to DynamoDB

3. **DynamoDB Metrics Storage**
   - Table: `cnv-metrics`
   - Stores: workload type, parameters, branches, methodCalls, timestamp
   - Used by LB for complexity estimation

---

### Metrics Collected

**Per Request:**
- `branches` - Number of branch targets executed
- `methodCalls` - Number of instrumented method invocations
- `workload` - Type (fractals/grayscott/dna)
- `params` - Workload parameters (w, h, iterations, size, sequences, etc.)
- `timestamp` - Request timestamp

**Storage:** DynamoDB table `cnv-metrics`

---

## AWS Configuration

### Auto-Scaling Parameters

**Environment Variables (Load Balancer):**
```bash
LB_MIN_WORKERS=1                    # Minimum cluster size
LB_MAX_WORKERS=X                    # Maximum cluster size
LB_SCALE_OUT_PRESSURE=Y             # Threshold to add workers
LB_SCALE_IN_PRESSURE=2Y             # Threshold to remove workers
LB_SCALER_PERIOD_MS=Z               # Check interval (10 seconds)
LB_SCALER_COOLDOWN_MS=T             # Cooldown between scaling actions (60 seconds)
```

**Scaling Logic:**
- **Cluster Pressure Calculation:** Sum of estimated complexity across all in-flight requests on all workers
- **Scale Out:** When `clusterPressure > LB_SCALE_OUT_PRESSURE`, launch new worker
- **Scale In:** When `clusterPressure < LB_SCALE_IN_PRESSURE` and workers > min, terminate idle worker
- **Cooldown:** Prevents rapid scaling oscillations

### Load Balancing Strategy

**Complexity-Based Routing:**

1. **Complexity Estimation:**
   ```
   complexity = median(historical_branches + historical_methodCalls) + predictedLoops
   ```

2. **Loop Prediction Formulas:**
   - **Fractals:** `(width × height × iterations) / 2`
   - **GrayScott:** `size² × maxIterations`
   - **DNA:** `(seq1_length × seq2_length) / 10`

3. **Worker Selection:**
   - Choose worker with lowest current load (sum of in-flight request complexities)

4. **Bucketing Strategy:**
   - Group requests by parameter magnitude (e.g., `px=100000,it=100`)
   - Enables matching similar workloads even without exact parameter match

### DynamoDB Configuration

**Table Schema:**
```
Primary Key: requestId (String, UUID)
Attributes:
  - workload (String)
  - timestamp (Number)
  - branches (Number)     
  - methodCalls (Number)   
  - params (Map)
```

**Access Pattern:**
- Workers: Write metrics after each request
- LB: Scan with filter for specific workload (limited to 200 samples)

### Security Groups

**Worker Security Group (`CNV-Worker-SG`):**
- Port 22 (SSH): `0.0.0.0/0`
- Port 8000 (HTTP): `0.0.0.0/0`

**LB Security Group (`CNV-LB-SG`):**
- Port 22 (SSH): `0.0.0.0/0`
- Port 8000 (HTTP): `0.0.0.0/0`

### IAM Roles

**Worker Role (`CNV-Worker-Role`):**
- Policy: `AmazonDynamoDBFullAccess` (for metrics storage)

**LB Role (`CNV-LB-Role`):**
- EC2 permissions: `DescribeInstances`, `RunInstances`, `TerminateInstances`, `CreateTags`
- DynamoDB permissions: Full access (for metrics reading)
- IAM permission: `PassRole` for `CNV-Worker-Role` (to launch workers)

---

## Implementation Status (Checkpoint)

###  Completed

1. **Instrumentation:**
   -  MetricsTool with branch and method counting
   -  Parameter-based loop prediction
   -  DynamoDB metrics storage

2. **Load Balancer:**
   -  EC2 worker discovery by tag
   -  Complexity-based request routing
   -  In-flight request tracking

3. **Auto-Scaler:**
   -  Pressure-based scaling decisions
   -  Launch template integration
   -  Worker lifecycle management

4. **Infrastructure:**
   -  Custom Worker AMI with instrumented workloads
   -  Automated deployment script
   -  DynamoDB integration
   -  IAM roles and security groups

---

## Key Design Decisions

### 1. Branch Instrumentation
**Rationale:** Branch targets represent control flow convergence points, providing sufficient granularity for complexity estimation while reducing instrumentation overhead.

### 2. Parameter-Based Loop Prediction
**Rationale:** Workload loop counts are mathematically predictable from input parameters. This eliminates the need for expensive runtime loop counting.

### 3. Hybrid Complexity Estimation
**Rationale:** Combines historical instrumentation data (branches + methods) with predicted loops for best accuracy. Falls back to pure prediction when historical data unavailable.

### 4. Magnitude-Based Bucketing
**Rationale:** Groups similar workloads by order of magnitude (e.g., 100K pixels vs 1M pixels) to maximize historical data reuse while maintaining accuracy.

### 5. Pressure-Based Autoscaling
**Rationale:** Aggregate cluster pressure (sum of in-flight complexities) provides better scaling signals than simple request count or CPU-based metrics.

---

## Deployment Instructions

### Prerequisites
- AWS CLI configured with credentials
- Maven 3.6+
- Java 11
- SSH key pair created in AWS

### Deploy to AWS

```bash
# Build project
mvn clean package

# Deploy infrastructure (creates AMI, LB, workers)
./scripts/deploy-aws.sh

# Get LB IP
source .aws-deployment-vars.sh
echo "LB IP: $LB_IP"

# Test
curl http://$LB_IP:8000/fractals?w=512&h=512&iterations=50
```

### Cleanup 

```bash
./scripts/cleanup-aws.sh
```

---

## Testing

### Manual Testing

```bash
curl "http://$LB_IP:8000/fractals?w=800&h=600&iterations=100"
curl "http://$LB_IP:8000/grayscott?size=256&maxIterations=1000&f=0.030&k=0.062&stopOnExtinction=false&seedMode=stripe"
curl "http://$LB_IP:8000/dna?seq1=seq1:ATGCATGC&seq2=seq2:ATGCATGC&minLength=3&stopOnFirst=false"

# Verify metrics in DynamoDB
aws dynamodb scan --table-name cnv-metrics --limit 5
```

### Load Testing

```bash
for i in {1..15}; do
  curl "http://$LB_IP:8000/fractals?w=1200&h=1200&iterations=300" &
done

watch -n 5 'aws ec2 describe-instances --filters "Name=tag:cnv-role,Values=worker" "Name=instance-state-name,Values=running" --query "Reservations[*].Instances[*].[InstanceId,State.Name]" --output table'
```

---

## Authors

Group 09:
- Inês Alves ist1107157;
- Diogo Rodrigues ist1106147; 
- Luca Dallalana ist1106378.
