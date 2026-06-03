#!/usr/bin/env python3
import boto3, time
from datetime import datetime, timezone, timedelta

DURATION = 780
INTERVAL = 60
LOG      = f"/tmp/cnv_monitor_{int(time.time())}.log"

ec2 = boto3.client("ec2",        region_name="us-east-1")
cw  = boto3.client("cloudwatch", region_name="us-east-1")

def workers():
    resp = ec2.describe_instances(Filters=[
        {"Name": "tag-key",             "Values": ["cnv-role"]},
        {"Name": "instance-state-name", "Values": ["running", "pending"]},
    ])
    return [{"id": i["InstanceId"], "state": i["State"]["Name"]}
            for r in resp["Reservations"] for i in r["Instances"]]

def cpu(ids, period=60):
    if not ids:
        return {}
    now = datetime.now(timezone.utc)
    out = {}
    for iid in ids:
        pts = cw.get_metric_statistics(
            Namespace="AWS/EC2", MetricName="CPUUtilization",
            Dimensions=[{"Name": "InstanceId", "Value": iid}],
            StartTime=now - timedelta(seconds=period * 3),
            EndTime=now, Period=period,
            Statistics=["Average", "Maximum"],
        )["Datapoints"]
        if pts:
            latest = max(pts, key=lambda p: p["Timestamp"])
            out[iid] = (round(latest["Average"], 2), round(latest["Maximum"], 2))
        else:
            out[iid] = (None, None)
    return out

end = time.time() + DURATION
while time.time() < end:
    try:
        ws   = workers()
        cpus = cpu([w["id"] for w in ws])
        ts   = time.strftime("%H:%M:%S")
        line = f"{ts}  workers={len(ws)}"
        for w in ws:
            avg, mx = cpus.get(w["id"], (None, None))
            line += f"  [{w['id'][-6:]} {w['state']} avg={avg}% max={mx}%]"
        print(line, flush=True)
        with open(LOG, "a") as f:
            f.write(line + "\n")
    except Exception as e:
        print(f"{time.strftime('%H:%M:%S')} error: {e}", flush=True)
    time.sleep(INTERVAL)
