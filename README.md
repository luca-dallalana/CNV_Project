# Nature@Cloud

An auto-scaling load balancer deployed on AWS that distributes three compute-intensive workloads across a dynamically managed pool of EC2 worker instances. The system estimates the computational cost of each incoming request before scheduling it, switches between load-concentration and load-spreading strategies based on cluster pressure, and falls back to AWS Lambda for low-complexity requests when all EC2 workers are saturated.

Academic project — Cloud and Virtualization (CNV), IST Lisboa, 2025-2026 — Group 09

---

## Tech Stack

| Layer | Technologies |
|-------|-------------|
| Language | Java 11 |
| Build | Apache Maven (multi-module) |
| Bytecode instrumentation | Javassist |
| Cloud | AWS EC2, AWS Lambda, AWS DynamoDB, AWS CloudWatch |
| AWS SDK | AWS SDK for Java v2 |
| Analysis / scripting | Python (numpy, scipy), Bash |

---

## Features

- Distributes HTTP requests across a fleet of EC2 workers that scales between 1 and 6 instances
- Estimates each request's computational cost before routing it, using a 5-quantile model built from historical DynamoDB data
- Switches between **pack** scheduling (concentrate load on fewer workers at low pressure) and **spread** scheduling (minimize hotspots at high pressure)
- Falls back to AWS Lambda for low-complexity requests when all EC2 workers are at capacity
- Scales out after 3 consecutive high-pressure ticks; scales in after 3 consecutive low-pressure ticks — preventing oscillation
- Instruments worker JVMs at bytecode load time via a Javassist agent that counts instructions, branches, and method calls per request thread, with no changes to application source
- Serves three workloads: Julia Set fractal rendering (PNG), Gray-Scott reaction-diffusion simulation (PNG), and DNA sequence alignment (HTML)
- Supports a static-worker mode (`LB_STATIC_WORKERS`) for local testing without AWS

---

## Architecture / How It Works

The system has two server roles: a **load balancer** and one or more **workers**, both compiled from the same codebase.

**Request path (load balancer side):**
1. `LoadBalancerHandler` receives the request, parses parameters, and calls `DynamoDbComplexityEstimator` to produce a predicted complexity score.
2. `RequestScheduler` selects a worker. Under low pressure it uses **pack mode** — routing to the busiest worker still under the scale-out ceiling — to keep idle workers free for scale-in. Under high pressure it uses **spread mode** — routing to the least-loaded worker — to avoid hotspots.
3. The request is forwarded to the chosen worker. On a 5xx error the worker is excluded and the next attempt picks a different one (up to `LB_REQUEST_RETRY_COUNT` attempts). If no EC2 worker is available and the predicted complexity is below `LB_LAMBDA_COMPLEXITY_THRESHOLD`, the request is forwarded to the matching AWS Lambda function instead.

**Complexity estimation:**
`DynamoDbComplexityEstimator` periodically scans the `cnv-metrics` DynamoDB table and builds a per-workload 5-quantile model. Each sample in the table is a past request with its input parameters and measured instruction count. The estimator computes a workload-specific heuristic driver from the current request's parameters, finds which quantile bucket that driver falls into, and returns the median actual instruction count for that bucket. If the table has too few samples, it falls back to the per-workload global median, then to the raw heuristic.

Heuristic drivers:
- **fractals**: `w × h × min(iterations × 15, 1500)`
- **grayscott**: `size² × maxIterations × 225` (÷ 3 if `stopOnExtinction=true`)
- **dna**: `len(seq1) × len(seq2) × 40`

**Instrumentation (worker side):**
Workers are launched with a Javassist JVM agent (`-javaagent:javassist.jar=MetricsTool:...`). The agent transforms the bytecode of each workload class at load time, inserting counter increments at every basic block and method entry. Counters are stored in `ThreadLocal` variables so concurrent requests on the same worker do not interfere. After each request completes, the handler reads the counters, writes them to DynamoDB alongside the request parameters, and clears the thread-locals for the next request.

**Auto-scaler:**
`AutoScaler` ticks every `LB_SCALER_PERIOD_MS` (5 s in production). Each tick it discovers healthy EC2 workers (by the `cnv-role=worker` tag), refreshes the worker registry, and computes cluster pressure as `totalQueuedWork / effectiveWorkers`. It also polls CloudWatch for average CPU utilization. Scale-out requires 3 consecutive ticks above `LB_SCALE_OUT_PRESSURE` or above `LB_CPU_SCALE_OUT_THRESHOLD`; scale-in requires 3 consecutive ticks below both `LB_SCALE_IN_PRESSURE` and `LB_CPU_SCALE_IN_THRESHOLD`. On scale-in, the candidate worker is marked draining — it keeps serving in-flight requests and is terminated only when its in-flight count reaches zero.

---

## Getting Started

**Prerequisites:** Java 11+, Maven 3.x

```bash
git clone https://github.com/luca-dallalana/CNV_Project.git
cd CNV_Project
mvn clean package
```

This produces two fat JARs:
- `webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar`
- `javassist/target/javassist-1.0.0-SNAPSHOT-jar-with-dependencies.jar`

**Running locally (static worker mode — no AWS required):**

```bash
# Terminal 1 — worker on port 8000
java \
  -javaagent:javassist/target/javassist-1.0.0-SNAPSHOT-jar-with-dependencies.jar=MetricsTool:pt.ulisboa.tecnico.cnv.fractals,pt.ulisboa.tecnico.cnv.grayscott,pt.ulisboa.tecnico.cnv.dna:output \
  -cp webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  pt.ulisboa.tecnico.cnv.webserver.WorkerWebServer

# Terminal 2 — load balancer on port 9000 pointing at the local worker
LB_STATIC_WORKERS=localhost:8000 LB_PORT=9000 \
java -cp webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     pt.ulisboa.tecnico.cnv.webserver.WebServer
```

**Key environment variables:**

| Variable | Default | Description |
|----------|---------|-------------|
| `LB_STATIC_WORKERS` | *(empty)* | Comma-separated `host:port` list; enables static mode (no EC2) |
| `LB_PORT` | `8000` | Port the load balancer listens on |
| `LB_MIN_WORKERS` / `LB_MAX_WORKERS` | `1` / `6` | Worker fleet size bounds |
| `LB_SCALE_OUT_PRESSURE` | `30000000` | Per-worker queue pressure threshold for scale-out |
| `LB_SCALE_IN_PRESSURE` | `8000000` | Per-worker queue pressure threshold for scale-in |
| `LB_HARD_CEILING` | `3 × scale-out` | Max estimated queue per worker; requests above this go elsewhere |
| `LB_LAMBDA_ENABLED` | `false` | Enable Lambda fallback |
| `LB_LAMBDA_COMPLEXITY_THRESHOLD` | `1000000` | Max complexity routed to Lambda |
| `LB_CPU_SCALE_OUT_THRESHOLD` | `60.0` | CPU % that triggers scale-out |
| `LB_CPU_SCALE_IN_THRESHOLD` | `20.0` | CPU % below which scale-in is considered |
| `AWS_REGION` | `us-east-1` | AWS region for EC2, DynamoDB, Lambda |
| `CNV_METRICS_TABLE` | `cnv-metrics` | DynamoDB table name for instrumentation data |

**AWS deployment (full from-scratch):**

```bash
bash scripts/deploy-aws.sh
```

---

## Usage

Once the load balancer is running (replace `$LB_IP:$LB_PORT` with `localhost:9000` for local mode):

**Julia Set fractal — returns a base64-encoded PNG:**
```bash
curl "http://$LB_IP:$LB_PORT/fractals?w=800&h=600&iterations=100"
```

**Gray-Scott reaction-diffusion — returns a base64-encoded PNG:**
```bash
curl "http://$LB_IP:$LB_PORT/grayscott?size=256&maxIterations=1000&f=0.030&k=0.062&stopOnExtinction=false&seedMode=stripe"
```

**DNA sequence alignment — returns an HTML report:**
```bash
curl "http://$LB_IP:$LB_PORT/dna?seq1=human:ATGCATGCTAGC&seq2=chimp:ATGCATGCAAGC&minLength=3&stopOnFirst=false"
```

**Verify instrumentation data in DynamoDB:**
```bash
aws dynamodb scan --table-name cnv-metrics --limit 5
```

**Trigger a load experiment (720 s, mixed workloads):**
```bash
bash scripts/run-experiment.sh
```

---

## What I Learned / Challenges

The hardest problem was designing a complexity estimator that works well before the system has seen many requests. A simple linear regression on request parameters underfit badly because the instruction count varies by several orders of magnitude across workload sizes — a 64×64 fractal and a 2048×2048 fractal are not on the same scale. The solution was the 5-quantile model: sort historical samples by their heuristic driver value, divide them into five buckets, and return the median actual instruction count from the matching bucket. This turned out to be far more robust than regression because it adapts to whatever distribution of request sizes the DynamoDB table actually contains, with no assumptions about linearity.

The Javassist instrumentation was also non-trivial. Inserting metric counters at the basic-block level inside a running JVM — via a `premain` agent and bytecode transformation — required understanding Javassist's control-flow API and being careful about which methods to instrument (skipping getters, setters, and constructors to reduce overhead) and how to isolate per-request counters across concurrent threads using `ThreadLocal`. Getting the agent to cleanly hand off metrics to the HTTP handler after each request, and reset the thread-locals before the next one, took several iterations to get right.

---

## Authors

Group 09:
- Inês Alves (ist1107157)
- Diogo Rodrigues (ist1106147)
- Luca Dallalana (ist1106378)

---

## Repository Organization

```
pom.xml                  Root Maven POM
fractals/                Julia Set fractal workload
dna/                     DNA sequence alignment workload
grayscott/               Gray-Scott reaction-diffusion workload
javassist/               Bytecode instrumentation agent
webserver/               Load balancer and worker server
scripts/                 Deployment, testing, and analysis scripts
report.tex               LaTeX source for the project report
```

---

## Workloads

### `fractals/`
- `JuliaFractal.java` — fractal computation engine
- `FractalsHandler.java` — HTTP handler + AWS Lambda entry point; returns base64-encoded PNG

### `dna/`
- `Dna.java` — seed-and-extend sequence alignment algorithm
- `DnaHandler.java` — HTTP handler + AWS Lambda entry point
- `DnaHtmlRenderer.java` — formats results as HTML
- `src/main/resources/*.fasta` — bundled sample sequences (human, chimpanzee, norway rat HBB)

### `grayscott/`
- `GrayScott.java` — reaction-diffusion simulation engine
- `GrayScottHandler.java` — HTTP handler + AWS Lambda entry point; returns base64-encoded PNG

All three handlers implement both `HttpHandler` (worker mode) and `RequestHandler<Map<String,String>,String>` (Lambda mode).

---

## Instrumentation (`javassist/`)

Javassist-based JVM agent that instruments worker bytecode at load time to count per-thread metrics.

| File | Role |
|------|------|
| `JavassistAgent.java` | JVM agent `premain`; selects and registers the transformer |
| `AbstractJavassistTool.java` | Base transformer; walks basic blocks via Javassist `ControlFlow` |
| `MetricsTool.java` | Counts `instructions`, `branches`, `methodCalls` per thread using `ThreadLocal` |
| `CodeDumper.java` | Debug tool; logs every intercepted class/method/block |
| `DynamoDbMetricsStore.java` | Writes per-request metrics to DynamoDB (`cnv-metrics` table) after each handler call |

Workers are launched with:
```
-javaagent:javassist.jar=MetricsTool:pt.ulisboa.tecnico.cnv.fractals,pt.ulisboa.tecnico.cnv.grayscott,pt.ulisboa.tecnico.cnv.dna:output
```

---

## Load Balancer (`webserver/`)

### Entry points
- `WorkerWebServer.java` — worker server; registers the three workload handlers on port 8000
- `WebServer.java` — load balancer; wires all LB components and starts the server

### LB components (`lb/` package)

| File | Role |
|------|------|
| `LoadBalancerHandler.java` | Per-request logic: estimate complexity, select worker, retry loop, Lambda fallback |
| `RequestScheduler.java` | Worker selection: **pack** strategy (low pressure) vs **spread** strategy (high pressure) |
| `DynamoDbComplexityEstimator.java` | 5-quantile model over DynamoDB history; heuristic fallback per workload |
| `AutoScaler.java` | Periodic tick: scale-out/in based on queue pressure and CPU utilization |
| `WorkerNode.java` | Per-worker thread-safe state: inflight requests, estimated queued work, draining flag |
| `WorkerRegistry.java` | ConcurrentHashMap of active workers; refreshed each autoscaler tick |
| `Ec2WorkerDiscovery.java` | Discovers workers by EC2 tag `cnv-role=worker`; launches/terminates instances |
| `CloudWatchMetricsPoller.java` | Polls `CPUUtilization` per instance (60 s cache) |
| `LambdaInvoker.java` | Invokes Lambda functions for fallback routing |
| `LbConfig.java` | All configuration read from environment variables |
| `StaticWorkerDiscovery.java` | Local/static worker mode via `LB_STATIC_WORKERS` env var |
| `WorkerHttpClient.java` | Forwards requests to workers; health probing via `/test` |
| `WorkerDiscovery.java` | Discovery interface |
| `QueryParams.java` | URL query string parser |

### Scheduling strategy

- **Pack mode** (pressure < `LB_SCALE_OUT_PRESSURE`): route to the busiest worker still under ceiling — concentrates load to avoid spinning up new instances unnecessarily
- **Spread mode** (pressure ≥ `LB_SCALE_OUT_PRESSURE`): route to the least-loaded worker (min estimated queue)
- Workers above `LB_HARD_CEILING` or marked draining are excluded

### Auto-scaler logic

- Ticks every `LB_SCALER_PERIOD_MS` (5 s in deployment)
- Requires 3 consecutive high-pressure ticks before scaling out; 3 low-pressure ticks before scaling in
- Scale-in: marks a candidate worker as draining; terminates it the next tick once in-flight requests reach zero
- `pendingWorkerCount` tracks launched-but-not-yet-healthy workers to prevent over-provisioning

---

## Scripts (`scripts/`)

### Deployment
| Script | Purpose |
|--------|---------|
| `deploy-aws.sh` | Full from-scratch AWS deployment: IAM roles, security groups, worker AMI, launch template, LB instance, initial workers |
| `redeploy-instances.sh` | Redeploy LB and workers from an existing AMI |
| `deploy-lambda.sh` | Package and deploy Lambda functions |
| `cleanup-aws.sh` | Tear down all AWS resources |
| `cleanup-instances.sh` | Terminate running worker instances only |
| `start-worker.sh` | Start worker server locally |
| `start-lb.sh` | Start LB locally |

### Testing & data collection
| Script | Purpose |
|--------|---------|
| `batch-test.sh` | 3 repetitions per workload tier (canonical parameter sets); targets LB or single worker |
| `batch-test-varied.sh` | 2 additional parameter variants per tier; used to diversify DynamoDB training samples |
| `test-aws-deployment.sh` | Smoke test against live LB |
| `concurrency-test.py` | Calibrates per-worker concurrency ceiling by sending N simultaneous requests to a single worker |

### Experiment
| Script | Purpose |
|--------|---------|
| `run-experiment.sh` | 12-minute mixed-workload experiment: streams LB logs via SSH, runs CloudWatch monitor, runs `loadtest-ec2.py` for 720 s, then `analyze.py` |
| `loadtest-ec2.py` | Concurrent load generator: FRAC-L, FRAC-M, GS-L, GS-M, DNA-XL threads looping for the experiment duration |
| `monitor.py` | Polls CloudWatch `CPUUtilization` during experiment and writes timestamped data |

### Analysis
| Script | Purpose |
|--------|---------|
| `analyze.py` | Parses LB log and CloudWatch data; prints scale-out/in timeline, per-worker load distribution, routing breakdown (EC2 vs Lambda) |
| `check-estimates.py` | Scans DynamoDB; prints driver/estimated-Y/actual-Y per sample, Spearman rank correlation, MAE |
| `regression.py` | OLS and NNLS regression on DynamoDB samples to validate complexity feature weights |

---

## AWS Deployment Configuration

Key values used in production (set in `lb-env.conf` on the LB instance):

| Variable | Value |
|----------|-------|
| `LB_SCALE_OUT_PRESSURE` | 500000000 |
| `LB_SCALE_IN_PRESSURE` | 8000000 |
| `LB_HARD_CEILING` | 1500000000 |
| `LB_MIN_WORKERS` / `LB_MAX_WORKERS` | 1 / 6 |
| `LB_LAMBDA_ENABLED` | true |
| `LB_LAMBDA_COMPLEXITY_THRESHOLD` | 20000000000 |
| `LB_CPU_SCALE_OUT_THRESHOLD` | 60% |
| `LB_CPU_SCALE_IN_THRESHOLD` | 20% |
| `LB_SCALER_PERIOD_MS` | 5000 |
| `LB_SCALER_COOLDOWN_MS` | 30000 |
| `LB_REQUEST_RETRY_COUNT` | 60 |
