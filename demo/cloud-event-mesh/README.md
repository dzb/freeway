# Cloud Event Mesh Demo

Two **separate JVM processes** exchanging events over two independent
channels — the minimal end-to-end proof of the "publish on one node, receive
on another" story:

```
┌────────────────────────┐         WS mesh (CloudEvents 1.0)         ┌────────────────────────┐
│  Node A  (publisher)   │  ────────────────────────────────────────► │  Node B  (subscriber)  │
│  port 18081            │                                            │  port 18080            │
│  CloudEventsModule     │         Kafka broker (freeway-mq-kafka)    │  CloudEventsModule     │
│  KafkaModule           │  ────────────────────────────────────────► │  KafkaModule           │
└────────────────────────┘        127.0.0.1:9092                      └────────────────────────┘
```

Both channels are installed on both nodes; a single `EventBus.publish`
fans out over **both** — the WS mesh (real-time, at-most-once) and the
Kafka bridge (durable, at-least-once). Node B's subscriber fires once per
channel, so each logical event is delivered twice (one copy per channel) —
the delivery counter in B's log proves both paths end-to-end.

## What it demonstrates

| Capability | Where it shows |
|---|---|
| Cross-JVM publish → subscribe (WS mesh) | `Greeting[bob]` + `hello-topic` arrive at B over CE frames |
| Cross-JVM publish → subscribe (Kafka) | the same two events arrive at B via the broker |
| `@Topic` routing key | `Greeting` maps to `greet.hello`; B subscribes the `greet.` prefix |
| `Keyed` → CE `subject` / Kafka record key | `key()` = `name`; per-aggregate ordering on both channels |
| Type allowlists gate deserialization | `EVENTS_ALLOWED_TYPES` (CE) and `freeway.kafka.allowed-event-types` (Kafka) must name the type |
| Silent-partition checklist | B needs module + enabled + subscriptions + allowlist; any miss = no delivery, no error |

## Prerequisites

- JDK 25
- Freeway artifacts in the local Maven repo:
  ```bash
  cd freeway && mvn install -DskipTests        # core (incl. freeway-cloud)
  cd freeway-ext && mvn install -DskipTests -pl freeway-mq-kafka -am
  ```
- (Kafka channel only) a broker at `127.0.0.1:9092`. Podman one-liner:
  ```bash
  podman run -d --name kafka-test -p 9092:9092 \
    -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
    -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
    -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://127.0.0.1:9092 \
    -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
    -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
    -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
    -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
    -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
    -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
    -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
    docker.io/apache/kafka:4.1.2
  ```
  Without a broker the WS-mesh channel still works; the Kafka subscriber
  simply logs connect retries.

## Run

```bash
cd demo/cloud-event-mesh
./run.sh
```

Expected output (node B deliveries):

```
[B] Greeting delivered #1: Greeting[name=bob]
[B] topic payload delivered #2: hello-topic
[B] Greeting delivered #3: Greeting[name=bob]
[B] topic payload delivered #4: hello-topic
```

Deliveries #1/#2 arrive via the WS mesh (fast), #3/#4 via Kafka — order
between the two channels is not guaranteed, but each logical event is
delivered once per channel. Exactly four deliveries proves both channels
end-to-end. If a channel is missing (e.g. only 2 deliveries), re-check the
silent-partition checklist below.

## Manual run (two terminals)

```bash
# terminal 1 — subscriber
mvn -q package -DskipTests
java -cp target/cloud-event-mesh-1.0-SNAPSHOT.jar demo.NodeB

# terminal 2 — publisher (after B prints "node ready")
java -cp target/cloud-event-mesh-1.0-SNAPSHOT.jar demo.NodeA
```

## Node configuration (the silent-partition checklist)

| Key | Node A | Node B |
|---|---|---|
| `freeway.http.server.port` | `18081` | `18080` |
| `freeway.cloud.events.enabled` | `true` | `true` |
| `freeway.cloud.events.peers` | `127.0.0.1:18080` | *(none — waits for inbound)* |
| `freeway.cloud.events.subscriptions` | `""` (outbound-only) | `greet.` |
| `freeway.cloud.events.allowed-types` | *(none — no inbound)* | `demo.Events$Greeting` |
| `freeway.cloud.events.allowed-topics` | *(none — no inbound)* | `greet.` |
| `freeway.cloud.events.token` | *(none — demo runs on loopback)* | *(same value on both nodes)* |
| `freeway.kafka.bootstrap-servers` | `127.0.0.1:9092` | `127.0.0.1:9092` |
| `freeway.kafka.client-id` | `node-a` | `node-b` |
| `freeway.kafka.topics` | `greet.hello` | `greet.hello` |
| `freeway.kafka.allowed-event-types` | *(none — no inbound)* | `demo.Events$Greeting,java.lang.String` |

Rules that bite (see DEVELOPER-GUIDE, "CloudEventBus"):

1. **A remote node receives only what *its own* `subscriptions` declares.**
   A local `EventBus.subscribe` alone is not enough.
2. **CLASS events additionally need the receiver's `allowed-types`** — CE
   and Kafka gates are independent; both must name the type.
3. **TOPIC payloads have their own gate**: `allowed-topics` is a prefix list
   like `subscriptions`, and an empty value accepts every topic. The two
   allowlists are independent — `allowed-types` says nothing about topics.
4. **The Kafka allowlist gates the TOPIC payload type too** — a `String`
   payload requires `java.lang.String` in `allowed-event-types`.
5. Every miss in the checklist above is a **silent partition**, not an error.

A node that declares `subscriptions` logs a warning at startup for each gate
it left open (no `allowed-types`, no `allowed-topics`, no `token`) — an
endpoint that accepts inbound from any peer should say so out loud.

## Cleanup

```bash
./run.sh   # its trap kills node B; node A exits by itself
pkill -f cloud-event-mesh   # if anything lingers
```
