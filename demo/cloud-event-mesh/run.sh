#!/usr/bin/env bash
# Cross-JVM event-mesh demo launcher.
#
# Usage:
#   ./run.sh            # build + start node B (subscriber) + node A (publisher)
#   ./run.sh --no-build # skip the maven package step
#
# Requires: JDK 25, local Maven repo with freeway 1.3.10 artifacts installed
# (mvn install in the repo root first), and — for the Kafka channel — a
# broker reachable at 127.0.0.1:9092 (e.g. podman run -d --name kafka-test
# -p 9092:9092 ... apache/kafka:4.1.2). Without a broker the WS mesh channel
# still works; the Kafka subscriber logs connection retries.
set -euo pipefail
# The JVM inherits the Windows console code page (GBK on a zh-CN host), which
# makes the log invalid UTF-8 and GNU grep degrade to "Binary file matches".
# Pin the console streams to UTF-8 and grep with -a so the output is readable
# regardless of the host locale.
cd "$(dirname "$0")"

if [[ "${1:-}" != "--no-build" ]]; then
  mvn -q package -DskipTests
fi

echo "== starting node B (subscriber, port 18080) =="
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp target/cloud-event-mesh-1.0-SNAPSHOT.jar demo.NodeB > /tmp/nodeB.log 2>&1 &
NODE_B_PID=$!
trap 'kill "$NODE_B_PID" 2>/dev/null || true' EXIT

# wait for B's mesh endpoint + subscriber to be ready
for i in $(seq 1 30); do
  grep -aq "\[B\] node ready" /tmp/nodeB.log && break
  sleep 1
done
grep -aq "\[B\] node ready" /tmp/nodeB.log || { echo "node B failed to start:"; tail -20 /tmp/nodeB.log; exit 1; }

echo "== starting node A (publisher, port 18081) =="
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp target/cloud-event-mesh-1.0-SNAPSHOT.jar demo.NodeA > /tmp/nodeA.log 2>&1

echo "== node B deliveries =="
grep -aE "\[B\]" /tmp/nodeB.log || true
echo
echo "== node A publish log =="
grep -aE "\[A\]" /tmp/nodeA.log || true
