# CNV 2025-2026 — Nature@Cloud
**Group 09**

## Overview

Nature@Cloud is an auto-scaling load balancer deployed on AWS EC2 that distributes three compute-intensive workloads across dynamically managed worker instances, with AWS Lambda as a fallback for low-complexity requests.

---

## Repository Organization

```
pom.xml                  Root Maven POM
fractals/             Julia Set fractal workload
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
- `Dna.java` — Smith-Waterman style DP alignment algorithm
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
| `CloudWatchMetricsPoller.java` | Polls `CPUUtilization` per instance (60s cache) |
| `LambdaInvoker.java` | Invokes Lambda functions for fallback routing |
| `LbConfig.java` | All configuration read from environment variables |
| `StaticWorkerDiscovery.java` | Local/static worker mode via `LB_STATIC_WORKERS` env var |
| `WorkerHttpClient.java` | Forwards requests to workers; health probing via `/test` |
| `WorkerDiscovery.java` | Discovery interface |
| `QueryParams.java` | URL query string parser |

### Complexity estimation

`DynamoDbComplexityEstimator` uses:
1. A workload-specific heuristic driver from request parameters
2. A 5-quantile lookup over `(driver, Y)` pairs from DynamoDB history (where `Y = instructions + branches + methodCalls`)
3. Fallback to per-workload median, then raw heuristic

Heuristic formulas:
- **fractals**: `w × h × min(iterations × 15, 1500)`
- **grayscott**: `size² × maxIterations × 225` (or `÷ 3` if `stopOnExtinction=true`)
- **dna**: `len(seq1) × len(seq2) × 40`

### Scheduling strategy

- **Pack mode** (pressure < `LB_SCALE_OUT_PRESSURE`): route to the busiest worker still under ceiling — concentrates load to avoid spinning up new instances unnecessarily
- **Spread mode** (pressure ≥ `LB_SCALE_OUT_PRESSURE`): route to the least-loaded worker (min estimated queue)
- Workers above `LB_HARD_CEILING` or marked draining are excluded

### Auto-scaler logic

- Ticks every `LB_SCALER_PERIOD_MS` (5s in deployment)
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
| `run-experiment.sh` | 12-minute mixed-workload experiment: streams LB logs via SSH, runs CloudWatch monitor, runs `loadtest-ec2.py` for 720s, then `analyze.py` |
| `loadtest-ec2.py` | Concurrent load generator: FRAC-L, FRAC-M, GS-L, GS-M, DNA-XL threads looping for the experiment duration |
| `monitor.py` | Polls CloudWatch `CPUUtilization` during experiment and writes timestamped data |

### Analysis
| Script | Purpose |
|--------|---------|
| `analyze.py` | Parses LB log and CloudWatch data; prints scale-out/in timeline, per-worker load distribution, routing breakdown (EC2 vs Lambda) |
| `check-estimates.py` | Scans DynamoDB; prints driver/estimated-Y/actual-Y per sample, Spearman rank correlation, MAE |
| `regression.py` | OLS and NNLS regression on DynamoDB samples to validate complexity feature weights |

---

## Build

Requires Java 11+ and Maven 3.x.

```bash
mvn clean package
```

Produces:
- `webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar`
- `javassist/target/javassist-1.0.0-SNAPSHOT-jar-with-dependencies.jar`

---

## Running Locally (static worker mode)

```bash
# Terminal 1 — worker
java -javaagent:javassist/target/javassist-1.0.0-SNAPSHOT-jar-with-dependencies.jar=MetricsTool:pt.ulisboa.tecnico.cnv.fractals,pt.ulisboa.tecnico.cnv.grayscott,pt.ulisboa.tecnico.cnv.dna:output \
     -cp webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     pt.ulisboa.tecnico.cnv.webserver.WorkerWebServer

# Terminal 2 — load balancer
LB_STATIC_WORKERS=localhost:8000 LB_PORT=9000 \
java -cp webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     pt.ulisboa.tecnico.cnv.webserver.WebServer
```

---

## AWS Deployment

```bash
bash scripts/deploy-aws.sh
```

Key configuration values (set in `lb-env.conf` on the LB instance):

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
