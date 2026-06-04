#!/usr/bin/env python3
# Usage: python3 scripts/concurrency-test.py <worker-ip> [port]
import sys
import time
import statistics
import concurrent.futures
import urllib.request

WORKER = sys.argv[1] if len(sys.argv) > 1 else "localhost"
PORT   = sys.argv[2] if len(sys.argv) > 2 else "8000"
BASE   = f"http://{WORKER}:{PORT}"

WORKLOADS = {
    "fractals-XS": f"{BASE}/fractals?w=800&h=600&iterations=100",
    "fractals-S":  f"{BASE}/fractals?w=4000&h=2000&iterations=10",
    "grayscott-XS": f"{BASE}/grayscott?size=256&maxIterations=100&f=0.030&k=0.062&stopOnExtinction=true&seedMode=center",
}

CONCURRENCY_LEVELS = [1, 2, 4, 8]
TOTAL_REQUESTS     = 16

def fetch(url):
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(url, timeout=120) as r:
            r.read()
        return time.perf_counter() - t0
    except Exception as e:
        return None

def run_level(url, concurrency):
    times = []
    wall_start = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        results = list(pool.map(lambda _: fetch(url), range(TOTAL_REQUESTS)))
    wall = time.perf_counter() - wall_start
    times = [r for r in results if r is not None]
    errors = results.count(None)
    return times, wall, errors

print(f"Target: {BASE}  —  {TOTAL_REQUESTS} requests per concurrency level\n")

for label, url in WORKLOADS.items():
    print(f"{'='*64}")
    print(f"  {label}")
    print(f"{'='*64}")
    print(f"  {'conc':>5}  {'avg-ms':>8}  {'min-ms':>8}  {'max-ms':>8}  {'wall-s':>7}  {'tput':>8}  {'err':>4}")
    print(f"  {'-'*5}  {'-'*8}  {'-'*8}  {'-'*8}  {'-'*7}  {'-'*8}  {'-'*4}")

    baseline_avg = None
    for c in CONCURRENCY_LEVELS:
        times, wall, errors = run_level(url, c)
        if not times:
            print(f"  {c:>5}  {'N/A':>8}  {'N/A':>8}  {'N/A':>8}  {wall:>7.2f}  {'N/A':>8}  {errors:>4}")
            continue
        avg = statistics.mean(times) * 1000
        mn  = min(times) * 1000
        mx  = max(times) * 1000
        tput = len(times) / wall
        if baseline_avg is None:
            baseline_avg = avg
        slowdown = avg / baseline_avg
        print(f"  {c:>5}  {avg:>8.0f}  {mn:>8.0f}  {mx:>8.0f}  {wall:>7.2f}  {tput:>7.2f}/s  {errors:>4}  (x{slowdown:.2f})")
    print()
