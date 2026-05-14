#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR_PATH="${ROOT_DIR}/webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar"

if [[ ! -f "${JAR_PATH}" ]]; then
  echo "Missing LB jar: ${JAR_PATH}. Build with: mvn clean package"
  exit 1
fi

exec java -cp "${JAR_PATH}" pt.ulisboa.tecnico.cnv.webserver.WebServer
