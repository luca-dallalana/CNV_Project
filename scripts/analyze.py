#!/usr/bin/env python3
import re, sys
from collections import defaultdict
from pathlib import Path

req_log = Path(Path("/tmp/cnv_experiment_logpath.txt").read_text().strip())
lb_path = Path("/tmp/cnv_lb_logpath.txt")
lb_log  = Path(lb_path.read_text().strip()) if lb_path.exists() else None

requests = []
for line in req_log.read_text().splitlines():
    m = re.match(r'(\d+:\d+:\d+)\s+(\S+)\s+code=(\S+)\s+ms=(\d+)', line)
    if m:
        requests.append({"ts": m.group(1), "label": m.group(2),
                         "code": m.group(3), "ms": int(m.group(4))})

by_label = defaultdict(list)
for r in requests:
    by_label[r["label"]].append(r)

print("=" * 60)
print("  REQUEST SUMMARY")
print("=" * 60)
for label in sorted(by_label):
    items = by_label[label]
    ok    = [r for r in items if r["code"] == "200"]
    err   = [r for r in items if r["code"] != "200"]
    ms    = [r["ms"] for r in ok]
    print(f"\n  {label}")
    print(f"    total   : {len(items)}")
    print(f"    200 OK  : {len(ok)}")
    if err:
        print(f"    errors  : {len(err)}  ({', '.join(sorted(set(r['code'] for r in err)))})")
    if ms:
        print(f"    latency : min={min(ms)}  max={max(ms)}  "
              f"mean={int(sum(ms)/len(ms))}  p50={sorted(ms)[len(ms)//2]}")

if not (lb_log and lb_log.exists()):
    sys.exit(0)

lb_lines = lb_log.read_text().splitlines()

scale_outs, drains, terminates = [], [], []
as_events, lambda_routes, ec2_selects = [], 0, 0
worker_ticks = defaultdict(list)   # iid -> list of (ts, queued_work)
worker_first_seen = {}

for line in lb_lines:
    ts = re.search(r'(\d+:\d+:\d+)', line)
    if "[AS] pressure=" in line:
        m = re.search(r'pressure=(\d+), avgCpu=([\d.]+|n/a)', line)
        if m and ts:
            as_events.append({
                "ts": ts.group(1),
                "pressure": int(m.group(1)),
                "cpu": float(m.group(2)) if m.group(2) != "n/a" else None,
            })
    elif "[AS] workers:" in line and ts:
        for iid, qw in re.findall(r'(i-\w+)=(\d+)', line):
            worker_ticks[iid].append((ts.group(1), int(qw)))
            if iid not in worker_first_seen:
                worker_first_seen[iid] = ts.group(1)
    elif "Scale-out:" in line and ts:
        p  = re.search(r'pressure=(\d+)', line)
        tk = re.search(r'ticks=(\d+)', line)
        scale_outs.append({"ts": ts.group(1),
                           "pressure": int(p.group(1)) if p else None,
                           "ticks": int(tk.group(1)) if tk else None})
    elif "Draining worker:" in line and ts:
        iid = re.search(r'Draining worker: (i-\w+)', line)
        drains.append({"ts": ts.group(1), "id": iid.group(1) if iid else "?"})
    elif "Terminating drained worker:" in line and ts:
        iid = re.search(r'worker: (i-\w+)', line)
        terminates.append({"ts": ts.group(1), "id": iid.group(1) if iid else "?"})
    elif "Routing to Lambda:" in line:
        lambda_routes += 1
    elif "Selected worker:" in line:
        ec2_selects += 1

print("\n" + "=" * 60)
print("  AUTOSCALER TIMELINE")
print("=" * 60)

print(f"\n  scale-out events : {len(scale_outs)}")
for i, e in enumerate(scale_outs, 1):
    print(f"    #{i}  {e['ts']}  pressure={e['pressure']:,}  ticks={e['ticks']}")

print(f"\n  drain events     : {len(drains)}")
for e in drains:
    print(f"    {e['ts']}  {e['id']}")

print(f"\n  terminate events : {len(terminates)}")
for e in terminates:
    print(f"    {e['ts']}  {e['id']}")

print(f"\n  lambda routes : {lambda_routes}")
print(f"  ec2 selects   : {ec2_selects}")

if as_events:
    step = max(1, len(as_events) // 20)
    print("\n  pressure / cpu sample:")
    for ev in as_events[::step]:
        cpu = f"{ev['cpu']:.1f}%" if ev["cpu"] is not None else "n/a"
        print(f"    {ev['ts']}  {ev['pressure']/1e9:6.2f}B  {cpu}")

    pressures = [e["pressure"] for e in as_events if e["pressure"] > 0]
    cpus      = [e["cpu"]      for e in as_events if e["cpu"] is not None]
    if pressures:
        print(f"\n  pressure  min={min(pressures):,}  max={max(pressures):,}  mean={int(sum(pressures)/len(pressures)):,}")
    if cpus:
        print(f"  cpu       min={min(cpus):.1f}%  max={max(cpus):.1f}%  mean={sum(cpus)/len(cpus):.1f}%")

if worker_ticks:
    print("\n" + "=" * 60)
    print("  PER-WORKER QUEUED COMPLEXITY (5s ticks)")
    print("=" * 60)
    print(f"\n  {'worker':<16}  {'first seen':>10}  {'avg (B)':>10}  {'max (B)':>10}  {'active ticks':>12}")
    for iid in sorted(worker_ticks, key=lambda x: worker_first_seen[x]):
        ticks = worker_ticks[iid]
        loads = [qw for _, qw in ticks]
        avg = sum(loads) / len(loads) if loads else 0
        mx  = max(loads) if loads else 0
        active = sum(1 for q in loads if q > 0)
        print(f"  {iid:<16}  {worker_first_seen[iid]:>10}  {avg/1e9:>10.2f}  {mx/1e9:>10.2f}  {active:>10}/{len(loads)}")

print("\n" + "=" * 60)
print("  ROUTING BREAKDOWN")
print("=" * 60)
for label in sorted(by_label):
    ok   = [r for r in by_label[label] if r["code"] == "200"]
    if not ok:
        continue
    fast = [r for r in ok if r["ms"] < 15000]
    slow = [r for r in ok if r["ms"] >= 15000]
    print(f"\n  {label:<12}  ok={len(ok)}  fast={len(fast)}  slow={len(slow)}")
    if slow:
        ms = [r["ms"] for r in slow]
        print(f"    slow: min={min(ms)}  max={max(ms)}  mean={int(sum(ms)/len(ms))}")
