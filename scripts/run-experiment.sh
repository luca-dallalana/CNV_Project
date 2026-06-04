#!/usr/bin/env bash
# Usage: ./scripts/run-experiment.sh [LB_IP]
set -euo pipefail

LB_IP="${1:-35.173.138.24}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/cnv-key.pem}"
VENV="$(dirname "$0")/../.venv/bin/python3"
SCRIPTS="$(dirname "$0")"

LB_LOG="/tmp/cnv_lb_logs_$(date +%s).log"
echo "$LB_LOG" > /tmp/cnv_lb_logpath.txt

ssh -o StrictHostKeyChecking=no -i "$SSH_KEY" ec2-user@"$LB_IP" \
    "sudo journalctl -u cnv-lb.service -f --no-pager" > "$LB_LOG" 2>/dev/null &
SSH_PID=$!

"$VENV" "$SCRIPTS/monitor.py" &
MON_PID=$!
"$VENV" "$SCRIPTS/loadtest-ec2.py"

kill "$SSH_PID" "$MON_PID" 2>/dev/null || true
"$VENV" "$SCRIPTS/analyze.py"
