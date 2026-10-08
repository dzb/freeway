#!/usr/bin/env bash
# Standalone registry-server demo launcher.
#
# Usage:
#   ./run.sh            # build + start the registry server on port 19090
#   ./run.sh --no-build # skip the maven package step
#
# Requires: JDK 25 and matching freeway artifacts in the local Maven repo
# (mvn install -DskipTests in the freeway repo root first).
set -euo pipefail
cd "$(dirname "$0")"

if [[ "${1:-}" != "--no-build" ]]; then
  mvn -q package -DskipTests
fi

echo "== starting registry server on http://127.0.0.1:19090 =="
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -cp target/registry-server-1.0-SNAPSHOT.jar demo.RegistryServer
