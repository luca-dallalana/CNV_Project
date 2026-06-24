import time
import math
import os
import boto3
import numpy as np
from scipy.stats import spearmanr

REGION = os.environ.get("AWS_REGION", "us-east-1")
TABLE  = os.environ.get("CNV_METRICS_TABLE", "cnv-metrics")

client = boto3.client('dynamodb', region_name=REGION)
items  = []
resp   = client.scan(TableName=TABLE)
items += resp['Items']
while 'LastEvaluatedKey' in resp:
    time.sleep(5)
    resp   = client.scan(TableName=TABLE, ExclusiveStartKey=resp['LastEvaluatedKey'])
    items += resp['Items']

def get_param(item, key):
    return item.get('params', {}).get('M', {}).get(key, {}).get('S', '')

def predict_loops(workload, item):
    def p(k): return get_param(item, k)
    if workload == 'fractals':
        w  = max(1, int(p('w')  or 1))
        h  = max(1, int(p('h')  or 1))
        it = max(1, int(p('iterations') or 1))
        return (w * h * it) // 2
    if workload == 'grayscott':
        size = max(1, int(p('size') or 1))
        it   = max(1, int(p('maxIterations') or 1))
        return size * size * it
    if workload == 'dna':
        seq1 = p('seq1'); seq2 = p('seq2')
        ml   = max(1, int(p('minLength') or 1))
        idx1 = seq1.find(':'); idx2 = seq2.find(':')
        s1   = seq1[idx1+1:] if idx1 >= 0 else seq1
        s2   = seq2[idx2+1:] if idx2 >= 0 else seq2
        return (max(1, len(s1)) * max(1, len(s2)) * ml) // 10
    return 0

def params_summary(workload, item):
    def p(k): return get_param(item, k)
    if workload == 'fractals':
        return f"w={p('w')} h={p('h')} it={p('iterations')}"
    if workload == 'grayscott':
        return f"size={p('size')} maxIt={p('maxIterations')}"
    if workload == 'dna':
        return f"minLen={p('minLength')}"
    return ''

def list_median(lst):
    s = sorted(lst)
    n = len(s)
    if n == 0: return 0
    if n % 2 == 1: return s[n // 2]
    return (s[n // 2 - 1] + s[n // 2]) // 2

def build_model(pairs):
    n = len(pairs)
    if n < 5:
        return None
    pairs = sorted(pairs, key=lambda x: x[0])
    boundaries = []
    for i in range(1, 5):
        idx = math.floor(i * n / 5 + 0.5) - 1  # Java Math.round
        idx = max(0, min(n - 1, idx))
        boundaries.append(pairs[idx][0])
    buckets = [[] for _ in range(5)]
    for driver, complexity in pairs:
        q = next((i for i, b in enumerate(boundaries) if driver <= b), 4)
        buckets[q].append(complexity)
    return boundaries, [list_median(b) if b else 0 for b in buckets]

def find_quantile(driver, boundaries):
    return next((i for i, b in enumerate(boundaries) if driver <= b), len(boundaries))

rows = []
for item in items:
    try:
        wl         = item['workload']['S']
        complexity = (int(item['instructions']['N'])
                    + int(item['branches']['N'])
                    + int(item['methodCalls']['N']))
        exec_time  = int(item['executionTimeMs']['N'])
        driver     = predict_loops(wl, item)
        summary    = params_summary(wl, item)
        rows.append((wl, summary, driver, complexity, exec_time))
    except KeyError:
        pass

models = {}
for wl in ('fractals', 'grayscott', 'dna'):
    pairs = [(r[2], r[3]) for r in rows if r[0] == wl]
    m = build_model(pairs)
    if m:
        models[wl] = m

for wl in ('fractals', 'grayscott', 'dna'):
    subset = [(r[1], r[2], r[3], r[4]) for r in rows if r[0] == wl]
    if not subset:
        continue
    print(f"\n{'='*72}")
    print(f"  {wl.upper()}  (n={len(subset)})")
    print(f"{'='*72}")
    model = models.get(wl)
    if not model:
        print(f"  not enough samples for quantile model (need >= 5)")
        continue
    boundaries, qmedians = model
    print(f"  boundaries : {[f'{b:.3e}' for b in boundaries]}")
    print(f"  q-medians  : {[f'{m:.3e}' for m in qmedians]}")
    print()
    print(f"  {'params':<35} {'driver':>12} {'est-Y':>14} {'act-Y':>14} {'exec-ms':>8}")
    print(f"  {'-'*35} {'-'*12} {'-'*14} {'-'*14} {'-'*8}")
    ests, acts, times = [], [], []
    for summary, driver, complexity, exec_time in sorted(subset, key=lambda r: r[1]):
        est = qmedians[find_quantile(driver, boundaries)]
        ests.append(est); acts.append(complexity); times.append(exec_time)
        print(f"  {summary:<35} {driver:>12,.0f} {est:>14,.0f} {complexity:>14,.0f} {exec_time:>8,}")
    rho_y, _ = spearmanr(ests, acts)
    rho_t, _ = spearmanr(ests, times)
    mae      = np.mean(np.abs(np.array(ests, dtype=float) - np.array(acts, dtype=float)))
    print(f"\n  Spearman(est-Y, act-Y)   : {rho_y:.4f}")
    print(f"  Spearman(est-Y, exec-ms) : {rho_t:.4f}")
    print(f"  MAE (complexity units)   : {mae:.0f}")
