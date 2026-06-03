#!/usr/bin/env python3
import threading, time, urllib.request, urllib.parse, urllib.error, os

LB       = "35.173.138.24"
BASE     = f"http://{LB}:8000"
DURATION = 720

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

WDIR      = os.path.expanduser("~/Documents/IST/SD/CNV/LABS/Workloads")
SEQ_ECOLI = read_fasta(f"{WDIR}/genome-escherichia-coli-25k.fasta.txt")
SEQ_SALM  = read_fasta(f"{WDIR}/genome-salmonella-enterica-25k.fasta")

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
    threading.Thread(target=loop, args=("FRAC-L-1", f"{BASE}/fractals",
        {"w": "6000", "h": "6000", "iterations": "100000"}), daemon=True),
    threading.Thread(target=loop, args=("FRAC-L-2", f"{BASE}/fractals",
        {"w": "6000", "h": "6000", "iterations": "100000"}), daemon=True),
    threading.Thread(target=loop, args=("GS-L-1", f"{BASE}/grayscott",
        {"size": "256", "maxIterations": "2500", "f": "0.030", "k": "0.062",
         "stopOnExtinction": "false", "seedMode": "stripe"}), daemon=True),
    threading.Thread(target=loop, args=("GS-L-2", f"{BASE}/grayscott",
        {"size": "256", "maxIterations": "2500", "f": "0.030", "k": "0.062",
         "stopOnExtinction": "false", "seedMode": "stripe"}), daemon=True),
    threading.Thread(target=loop, args=("DNA-XL", f"{BASE}/dna",
        {"minLength": "250", "seq1": f"ecoli:{SEQ_ECOLI}",
         "seq2": f"salmonella:{SEQ_SALM}"}), daemon=True),
]

log(f"EXPERIMENT_START  workers={len(threads)}  duration={DURATION}s  mode=ec2-dominant")
for t in threads: t.start()
for t in threads: t.join()
log("EXPERIMENT_END")
