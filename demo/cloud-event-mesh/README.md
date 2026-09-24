# Cloud Event Mesh Demo

Two **separate JVM processes** exchanging CloudEvents 1.0 frames over the
WebSocket mesh — the minimal end-to-end proof of the plane model: a fact
crosses a process boundary only where the code says so, on a named plane.

```
┌────────────────────────────┐      WS mesh (CloudEvents 1.0)      ┌────────────────────────────┐
│  Node A  (publisher)       │  ────────────────────────────────►  │  Node B  (subscriber)      │
│  port 18081                │        /cloud/event                 │  port 18080                │
│                            │                                     │  CloudEventBus subscription│
│  fabric.publish(...)  ─────┼──── crosses the boundary ───►       │    → mirror line            │
│  bus.publish(...)     stays HERE                                 │  EventBus subscriber ──►   │
└────────────────────────────┘                                     └────────────────────────────┘
```

Node A publishes the same fact twice: once on the **mesh plane**
(`CloudEventBus.publish` — leaves the JVM) and once on the **local bus**
(`EventBus.publish` — never does, even with `CloudEventModule` loaded).
Node B receives the mesh fact through a declared `CloudEventSubscription`
and **mirrors it to its local bus on purpose** — one line, in the handler.

## What it demonstrates

| Capability | Where it shows |
|---|---|
| Plane separation: a local publish never crosses | `bus.publish` on A prints only on A — no peer receives it |
| Explicit cross-JVM broadcast | `fabric.publish("greet.hello", …)` arrives on B |
| Subscriptions are declared, not configured | B contributes a `CloudEventSubscription` — the same declaration drives the hello pull-prefix, the inbound gate, and delivery |
| The subscription table **is** the allowlist | the declared `Class` is the only type inbound frames deserialize into; undeclared topics are dropped unread |
| Remote facts become local facts only by mirroring | B's subscription handler calls `bus.publish(event)` — deliberate, visible, one line |
| `Defer` commit coupling | (not exercised here — see `CloudEventBusTest` in freeway-cloud: rollback inside a transaction = nothing on the wire) |

For the durable, per-key ordered stream plane see `freeway-ext/freeway-mq-kafka`
(`KafkaEvents`) — this demo keeps to the mesh.

## Prerequisites

- JDK 25
- Freeway artifacts in the local Maven repo:
  ```bash
  cd freeway && mvn install -DskipTests        # core (incl. freeway-cloud)
  ```
- **Windows notes** (verified on Windows 11 24H2 + Temurin JDK 25): run
  `run.sh` from Git Bash, not cmd/PowerShell (it uses `grep`, `seq`, `/tmp`).
  The JVM inherits the host console code page, so the script pins
  `stdout.encoding`/`stderr.encoding` to UTF-8 and greps with `-a`;
  otherwise GNU grep reports "Binary file matches" instead of the log lines.

## Run

```bash
cd demo/cloud-event-mesh
./run.sh
```

Expected output (B's log):

```
[B] mesh received #1: Greeting[name=bob] — mirroring to the local bus
[B] local bus heard the mirrored fact: Greeting[name=bob]
```

and from A:

```
[A] local bus heard (and NO peer receives this): Greeting[name=bob]
```

One mesh delivery + one mirror + one local-only print: the three planes doing
exactly what their call sites say. If B shows nothing, check the silent-
partition checklist: module placed on both nodes, `event.enabled`/`peers`
set on the dialing side, subscriptions declared on the receiving side, and a
`token` wherever meshes meet in production.

## Manual run (two terminals)

```bash
mvn -q package -DskipTests
java -cp target/cloud-event-mesh-1.0-SNAPSHOT.jar demo.NodeB    # subscriber
java -cp target/cloud-event-mesh-1.0-SNAPSHOT.jar demo.NodeA    # publisher
```
