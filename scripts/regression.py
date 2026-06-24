import time
import os
import boto3
import numpy as np
from sklearn.linear_model import LinearRegression
from scipy.optimize import nnls

REGION = os.environ.get("AWS_REGION", "us-east-1")
TABLE  = os.environ.get("CNV_METRICS_TABLE", "cnv-metrics")

client = boto3.client('dynamodb', region_name=REGION)

items = []
resp = client.scan(TableName=TABLE)
items += resp['Items']
while 'LastEvaluatedKey' in resp:
    time.sleep(5)
    resp = client.scan(TableName=TABLE, ExclusiveStartKey=resp['LastEvaluatedKey'])
    items += resp['Items']

def predict_loops(workload, params):
    if workload == 'fractals':
        w  = int(params.get('w',          {}).get('S', 800))
        h  = int(params.get('h',          {}).get('S', 600))
        it = int(params.get('iterations', {}).get('S', 100))
        return (w * h * it) // 2
    elif workload == 'grayscott':
        size = int(params.get('size',          {}).get('S', 256))
        it   = int(params.get('maxIterations', {}).get('S', 5000))
        return size * size * it
    elif workload == 'dna':
        seq1 = params.get('seq1', {}).get('S', '')
        seq2 = params.get('seq2', {}).get('S', '')
        min_len = int(params.get('minLength', {}).get('S', 1))
        return (len(seq1) * len(seq2) * min_len) // 10
    return 0

rows = []
skipped = 0
for item in items:
    try:
        workload     = item['workload']['S']
        instructions = int(item['instructions']['N'])
        branches     = int(item['branches']['N'])
        method_calls = int(item['methodCalls']['N'])
        exec_time    = int(item['executionTimeMs']['N'])
        params       = item.get('params', {}).get('M', {})
        loops        = predict_loops(workload, params)
        rows.append((instructions, branches, method_calls, loops, exec_time, workload))
    except KeyError:
        skipped += 1

print(f"Items loaded: {len(rows)}  (skipped {skipped} without all metrics)")

if len(rows) < 3:
    print("Not enough data for regression.")
    raise SystemExit(1)

X = np.array([[r[0], r[1], r[2], r[3]] for r in rows], dtype=float)
y = np.array([r[4] for r in rows], dtype=float)
model = LinearRegression().fit(X, y)
print(f"\nGlobal OLS (all workloads):")
print(f"  R²:                  {model.score(X, y):.4f}")
print(f"  instructions weight: {model.coef_[0]:.6e}")
print(f"  branches     weight: {model.coef_[1]:.6e}")
print(f"  methodCalls  weight: {model.coef_[2]:.6e}")
print(f"  predictLoops weight: {model.coef_[3]:.6e}")
print(f"  intercept:           {model.intercept_:.2f}")

X_bias = np.hstack([X, np.ones((len(X), 1))])
g_coef, _ = nnls(X_bias, y)
g_pred = X_bias @ g_coef
ss_res = np.sum((y - g_pred) ** 2)
ss_tot = np.sum((y - y.mean()) ** 2)
g_r2 = 1 - ss_res / ss_tot
print(f"\nGlobal NNLS (raw features):")
print(f"  R²:                  {g_r2:.4f}")
print(f"  instructions weight: {g_coef[0]:.6e}")
print(f"  branches     weight: {g_coef[1]:.6e}")
print(f"  methodCalls  weight: {g_coef[2]:.6e}")
print(f"  predictLoops weight: {g_coef[3]:.6e}")
print(f"  intercept:           {g_coef[4]:.2f}")

feature_means = X.mean(axis=0)
X_norm = X / feature_means
X_norm_bias = np.hstack([X_norm, np.ones((len(X_norm), 1))])
norm_coef, _ = nnls(X_norm_bias, y)
actual_weights = norm_coef[:4] / feature_means
norm_pred = X_norm_bias @ norm_coef
ss_res_n = np.sum((y - norm_pred) ** 2)
norm_r2 = 1 - ss_res_n / ss_tot
FEATURES = ['instructions', 'branches', 'methodCalls', 'predictLoops']
print(f"\nGlobal NNLS (mean-normalized):")
print(f"  R²:                  {norm_r2:.4f}")
for fname, fmean, w_norm, w_actual in zip(FEATURES, feature_means, norm_coef[:4], actual_weights):
    print(f"  {fname:<15}  mean={fmean:.3e}  norm_weight={w_norm:.4f}  actual_weight={w_actual:.6e}")
print(f"  intercept:           {norm_coef[4]:.2f}")

print(f"\nSingle-feature global regression (each feature alone, always positive weights):")
single_weights = []
for i, fname in enumerate(FEATURES):
    xi = X[:, i:i+1]
    m = LinearRegression().fit(xi, y)
    r2_sf = m.score(xi, y)
    single_weights.append(max(m.coef_[0], 0.0))
    print(f"  {fname:<15}  R²={r2_sf:.4f}  weight={m.coef_[0]:.6e}  intercept={m.intercept_:.2f}")

w_arr = np.array(single_weights)
pred_composite = X @ w_arr
ss_res_c = np.sum((y - pred_composite) ** 2)
composite_r2 = 1 - ss_res_c / ss_tot
print(f"\nComposite (sum of single-feature contributions):")
print(f"  complexity = ", end="")
parts = [f"{w:.3e}*{f}" for f, w in zip(FEATURES, single_weights)]
print(" + ".join(parts))
print(f"  R² of composite vs execTime: {composite_r2:.4f}")

print(f"\nComposite score by sample (to calibrate thresholds):")
for wl in ('fractals', 'grayscott', 'dna'):
    subset = [r for r in rows if r[5] == wl]
    if not subset:
        continue
    scores = [w_arr[0]*r[0] + w_arr[1]*r[1] + w_arr[2]*r[2] + w_arr[3]*r[3] for r in subset]
    times  = [r[4] for r in subset]
    print(f"  {wl}: score min={min(scores):.3e} max={max(scores):.3e}  execTime min={min(times)} max={max(times)} ms")

print("\nPer-workload breakdown:")
for wl in ('fractals', 'grayscott', 'dna'):
    subset = [r for r in rows if r[5] == wl]
    if subset:
        times = [r[4] for r in subset]
        print(f"  {wl}: n={len(subset)}, execTime min={min(times)}ms max={max(times)}ms mean={int(np.mean(times))}ms")

print("\nPer-workload regression (all 4 features, OLS vs NNLS):")
FEATURES = ['instructions', 'branches', 'methodCalls', 'predictLoops']
for wl in ('fractals', 'grayscott', 'dna'):
    subset = [r for r in rows if r[5] == wl]
    if len(subset) < 2:
        continue
    Xw = np.array([[r[0], r[1], r[2], r[3]] for r in subset], dtype=float)
    yw = np.array([r[4] for r in subset], dtype=float)

    ols = LinearRegression().fit(Xw, yw)
    ols_r2 = ols.score(Xw, yw)

    Xw_bias = np.hstack([Xw, np.ones((len(Xw), 1))])
    nnls_coef, _ = nnls(Xw_bias, yw)
    nnls_pred = Xw_bias @ nnls_coef
    ss_res = np.sum((yw - nnls_pred) ** 2)
    ss_tot = np.sum((yw - yw.mean()) ** 2)
    nnls_r2 = 1 - ss_res / ss_tot if ss_tot > 0 else 0

    print(f"\n  {wl}  (n={len(subset)})")
    print(f"  {'feature':<15}  {'OLS':>14}  {'NNLS':>14}")
    print(f"  {'-'*15}  {'-'*14}  {'-'*14}")
    for i, fname in enumerate(FEATURES):
        print(f"  {fname:<15}  {ols.coef_[i]:>14.6e}  {nnls_coef[i]:>14.6e}")
    print(f"  {'intercept':<15}  {ols.intercept_:>14.2f}  {nnls_coef[4]:>14.2f}")
    print(f"  R² OLS={ols_r2:.4f}  NNLS={nnls_r2:.4f}")
