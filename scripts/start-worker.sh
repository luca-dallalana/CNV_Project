#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WEB_JAR="${ROOT_DIR}/webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar"
AGENT_JAR="${ROOT_DIR}/javassist/target/javassist-1.0.0-SNAPSHOT-jar-with-dependencies.jar"
WORKER_PORT="${WORKER_PORT:-8000}"
METRICS_PACKAGES="${METRICS_PACKAGES:-pt.ulisboa.tecnico.cnv.dna,pt.ulisboa.tecnico.cnv.fractals,pt.ulisboa.tecnico.cnv.grayscott}"
METRICS_WRITE_DEST="${METRICS_WRITE_DEST:-.}"

if [[ ! -f "${WEB_JAR}" ]]; then
  echo "Missing worker jar: ${WEB_JAR}. Build with: mvn clean package"
  exit 1
fi

JAVA_AGENT_OPT=""
if [[ -f "${AGENT_JAR}" ]]; then
  JAVA_AGENT_OPT="-javaagent:${AGENT_JAR}=MetricsTool:${METRICS_PACKAGES}:${METRICS_WRITE_DEST}"
else
  echo "Warning: agent jar not found (${AGENT_JAR}). Starting worker without Javassist instrumentation."
fi

export WORKER_PORT

exec java ${JAVA_AGENT_OPT} -cp "${WEB_JAR}" pt.ulisboa.tecnico.cnv.webserver.WorkerWebServer
