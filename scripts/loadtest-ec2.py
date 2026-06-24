#!/usr/bin/env python3
# Usage: LB=<ip> [PORT=8000] [DURATION=720] python3 scripts/loadtest-ec2.py
import threading, time, urllib.request, urllib.parse, urllib.error, os, sys

LB       = os.environ.get("LB", "localhost")
PORT     = os.environ.get("PORT", "8000")
BASE     = f"http://{LB}:{PORT}"
DURATION = int(os.environ.get("DURATION", "720"))

LOGFILE = f"/tmp/cnv_experiment_{int(time.time())}.log"
open("/tmp/cnv_experiment_logpath.txt", "w").write(LOGFILE)

_lock = threading.Lock()

def log(msg):
    line = f"{time.strftime('%H:%M:%S')} {msg}"
    with _lock:
        print(line, flush=True)
        with open(LOGFILE, "a") as f:
            f.write(line + "\n")

def read_fasta(path):
    with open(path) as f:
        return "".join(l.strip() for l in f if not l.startswith(">"))

_script_dir = os.path.dirname(os.path.abspath(__file__))
WDIR     = os.environ.get("WORKLOADS_DIR", os.path.join(_script_dir, "..", "workloads"))
if not os.path.isdir(WDIR):
    print(f"ERROR: workloads directory not found: {WDIR}", file=sys.stderr)
    print("Set WORKLOADS_DIR to the directory containing the .fasta files.", file=sys.stderr)
    sys.exit(1)
SEQ_HMC  = read_fasta(os.path.join(WDIR, "human-mc-10k.fasta"))
SEQ_SARS = read_fasta(os.path.join(WDIR, "sars-10k.fasta"))

def do_request(label, url, params=None):
    full_url = url + "?" + urllib.parse.urlencode(params) if params else url
    t0 = time.time()
    try:
        with urllib.request.urlopen(full_url, timeout=660) as r:
            code = r.getcode(); r.read()
    except urllib.error.HTTPError as e:
        code = e.code
    except Exception as e:
        code = f"ERR:{type(e).__name__}"
    log(f"{label:<10} code={code}  ms={int((time.time()-t0)*1000)}")

def loop(label, url, params=None):
    end = time.time() + DURATION
    while time.time() < end:
        do_request(label, url, params)

threads = [
    threading.Thread(target=loop, args=("FRAC-L", f"{BASE}/fractals",
        {"w": "4000", "h": "4000", "iterations": "1000"}), daemon=True),
    threading.Thread(target=loop, args=("FRAC-M", f"{BASE}/fractals",
        {"w": "4000", "h": "2000", "iterations": "1000"}), daemon=True),
    threading.Thread(target=loop, args=("GS-L", f"{BASE}/grayscott",
        {"size": "256", "maxIterations": "2500", "f": "0.030", "k": "0.062",
         "stopOnExtinction": "false", "seedMode": "stripe"}), daemon=True),
    threading.Thread(target=loop, args=("GS-M", f"{BASE}/grayscott",
        {"size": "256", "maxIterations": "1000", "f": "0.030", "k": "0.062",
         "stopOnExtinction": "false", "seedMode": "stripe"}), daemon=True),
    threading.Thread(target=loop, args=("DNA-L", f"{BASE}/dna",
        {"minLength": "100", "seq1": f"human:{SEQ_HMC}",
         "seq2": f"sars:{SEQ_SARS}"}), daemon=True),
]

log(f"EXPERIMENT_START  workers={len(threads)}  duration={DURATION}s  mode=ec2-dominant")
for t in threads: t.start()
for t in threads: t.join()
log("EXPERIMENT_END")
