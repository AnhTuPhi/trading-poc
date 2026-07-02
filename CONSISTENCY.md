# CONSISTENCY.md — Scaling these engines across pods / VMs

Every POC in this repo is a **single-JVM, single-writer** design. That is deliberate: the hard
algorithm is easier to prove correct when there is exactly one thread mutating each aggregate.
But production runs multiple replicas — you scale a Kubernetes Deployment to N pods, or run the
service on M VMs behind a load balancer. The moment there are two instances, the single-writer
guarantee that makes these engines correct **is silently broken** unless we do something about
it.

This document explains what breaks, why, and the concrete pattern for each engine.

---

## The core tension

The POCs rely on one invariant:

> **Exactly one thread, in one process, mutates a given aggregate (an order book, a position, a
> margin account, a cash ledger).**

That is what makes them lock-free and deterministic. Now scale to N pods behind a round-robin
load balancer:

- Tick for VNM lands on pod A *and* pod B → two P&L pumps both mutate "the" VNM position → they
  diverge. Whose number do you push to the client?
- A buy order for account #42 is checked for margin on pod A while a price tick revalues the
  same account on pod B → the pre-trade check races the revaluation → you approve a trade that
  should have been rejected.
- Two pods both run the T+2 settlement sweep at start-of-day → the bucket is credited twice.

**Naïve horizontal scaling does not work for stateful financial aggregates.** Adding pods
without a partitioning or coordination strategy doesn't just lose performance — it produces
*wrong money numbers*.

## The one principle that fixes it

> **Keep single-writer, but make it single-writer *per aggregate across the whole cluster*,
> not per process.**

There are three ways to achieve that, in increasing order of coordination cost. Pick the
cheapest one that fits each engine.

| Strategy | How | Best for |
|----------|-----|----------|
| **A. Partition (shard) by key** | Route every message for a key to exactly one owning pod. | Order matching, P&L, stop-loss — high throughput, naturally keyed. |
| **B. Single-writer election (leader)** | One pod owns a job/aggregate at a time via a lock. | Settlement sweep, corporate-action EOD batch — low frequency, must-run-once. |
| **C. Optimistic concurrency + DB as source of truth** | No sticky routing; every write is a compare-and-set against a versioned row. | Margin account mutations when you can't guarantee sticky routing. |

Everything below is an application of A, B, or C.

---

## Strategy A — Partition by key (the default for the hot engines)

**Idea:** the aggregate key (symbol, or accountId, or symbol+account) determines which pod owns
it. All messages for that key go to that one pod. Within the pod, the existing single-writer
loop is unchanged and still correct.

```
                        ┌─ partition 0 (symbols A–H) ─► pod-0  (owns those books/positions)
Kafka topic (keyed) ────┼─ partition 1 (symbols I–P) ─► pod-1
   key = symbol         └─ partition 2 (symbols Q–Z) ─► pod-2
```

**How to implement in this stack:**
- Put ticks/orders on a **Kafka topic keyed by the aggregate key**. Kafka guarantees all
  messages with the same key go to the same partition, and each partition is consumed by exactly
  one consumer in the group. That consumer *is* the single writer. This is the natural home for
  POC 12 (P&L pump) and POC 13 (stop-loss `onTick`), and POC 11 if matching is per-symbol.
- Scaling = add partitions and pods; Kafka rebalances partitions across the group. The number of
  partitions is the ceiling on parallelism.

**What you must handle:**
- **Rebalance / handoff.** When a pod dies or you scale, a partition moves to another pod. The
  new owner must **rebuild in-memory state before processing** (resting orders, open positions,
  active triggers, `lastPrice`). Cold-starting a stop-loss engine without `lastPrice` can
  mis-fire on the first tick — see POC 13's tech-debt note. Rebuild from a snapshot + event
  replay (see *State recovery* below).
- **In-flight ordering during handoff.** Stop the old owner before the new owner starts (Kafka's
  cooperative rebalance + committing offsets handles the "process each message once" part).
- **K8s specifics.** Prefer a **StatefulSet** over a Deployment for partition-owning pods so
  identity (`pod-0`, `pod-1`) is stable and can map to a partition range. Set
  `terminationGracePeriodSeconds` long enough to flush a snapshot and commit offsets on
  `SIGTERM`. Use a `PodDisruptionBudget` so a node drain doesn't take all owners of a partition
  set at once.

---

## Strategy B — Single-writer election (for the once-a-day batch jobs)

POC 15 (settlement sweep) and POC 16 (corporate-action EOD pass) are **low-frequency,
must-run-exactly-once** jobs. Sharding is overkill; the risk is the *opposite* — every pod
firing the same sweep and double-crediting a bucket.

**Options, cheapest first:**
1. **Kubernetes `CronJob`** with `concurrencyPolicy: Forbid` — one pod, one run, no election
   logic in your code. This is the right answer for the settlement sweep and the corporate-action
   EOD batch in almost all cases.
2. **Leader election** (if the job must live inside the always-on service, not a separate Job):
   Redis lock (`SET key val NX PX ttl`) or a DB advisory lock. Only the lock holder runs the
   sweep. The stack already runs Redis (Jedis) — a `SET NX` with a TTL is enough.

**What you must handle:**
- **Idempotency is mandatory regardless of election.** Election reduces double-runs; it does not
  eliminate them (a leader can pause on GC, its lock expires, a new leader starts, the old one
  resumes). So the settlement sweep must be safe to run twice: key each settlement by
  `(account, settlementDate, bucketId)` and make the credit a conditional update
  ("settle bucket X **if** its status is still PENDING"). The POC's `Status` field on the bucket
  is exactly the guard — the distributed version just needs it to be a persisted,
  compare-and-set status, not an in-memory flag.
- **Clock.** Use an injected/coordinated clock, not each pod's `Instant.now()` — otherwise the
  margin grace window (POC 14) and audit timestamps drift per pod.

---

## Strategy C — Optimistic concurrency (when you can't guarantee sticky routing)

If margin mutations for an account can land on any pod (e.g. the pre-trade check runs on
whichever API pod took the request), don't try to make in-memory state authoritative. Make the
**database the single source of truth** and guard every write with a version.

```
UPDATE margin_account
   SET cash = ?, loan = ?, version = version + 1
 WHERE id = ? AND version = ?     -- fails if someone else moved first
```

- On a lost race (0 rows updated) → reload, re-run the pre-trade check, retry.
- The in-memory `Account` becomes a **cache**, not the truth. Oracle row = authority.
- This is slower per write but needs no sticky routing — good for the API-facing margin check.
  For the high-frequency revaluation loop, prefer Strategy A (shard accounts to pods) instead.

---

## State recovery — the part you cannot skip

Under Strategy A, a pod can lose its partition at any time (crash, scale-down, node drain, K8s
reschedule). The new owner must reconstruct exactly the in-memory state the POCs hold in the
heap. Pattern:

1. **Event-source the mutations.** Everything these POCs already do is append-only (trades,
   fills, fires, settlements, adjustment events). Persist that log (Kafka is both the transport
   *and* the log).
2. **Snapshot periodically** to bound replay. Write the in-memory aggregate (book / positions /
   triggers) to Redis or object storage every N seconds or M events.
3. **On partition assignment**: load latest snapshot → replay events since the snapshot's offset
   → *then* start consuming live. Only after this is `lastPrice` valid and stops safe to arm.

This is why the POCs' "append-only everything" decision matters beyond audit: **the audit log is
also the recovery log.**

## VM scaling vs pod scaling — same problem, different knobs

The consistency problem is identical whether replicas are K8s pods or VMs behind a load
balancer; only the mechanics differ:

| Concern | Kubernetes pods | VMs |
|---------|-----------------|-----|
| Stable identity for partition owners | `StatefulSet` (`pod-0…N`) | Fixed hostnames / a service registry (Consul/Eureka) |
| Run-once batch | `CronJob` + `concurrencyPolicy: Forbid` | One designated VM, or a distributed lock |
| Graceful handoff | `SIGTERM` + `terminationGracePeriodSeconds` + `preStop` hook | init/systemd stop hook that flushes snapshot + commits offsets |
| Avoid taking all owners at once | `PodDisruptionBudget`, anti-affinity | Staggered deploys, keep quorum |
| Leader election backend | Redis / DB lock (same either way) | Redis / DB lock (same either way) |

The takeaway: **pod autoscaling (HPA) is safe only for stateless request handlers.** The
stateful engines scale by *partition count*, not by pod count — you add throughput by adding
partitions and letting more pods each own fewer partitions, never by having more pods share the
same aggregate.

## Per-engine summary

| POC | Strategy | Partition key | Notes |
|-----|----------|---------------|-------|
| 11 order-matching | A | symbol | One book = one owner. Matching is inherently serial per symbol. |
| 12 realtime-pnl | A | account (or symbol) | Pump per shard; rebuild positions on handoff. |
| 13 stop-loss | A | symbol | Rebuild triggers **and `lastPrice`** before arming, or risk mis-fire. |
| 14 margin | A (revaluation) + C (API check) | account | Shard the revaluation loop; guard API-path writes with row versions. |
| 15 settlement | B | — | `CronJob` + `Forbid`; idempotent bucket status compare-and-set. |
| 16 corporate-actions | B | — | `CronJob` + `Forbid`; snapshot before/after externalised; idempotent by (symbol, effectiveDate). |

## What we are explicitly *not* claiming

- These POCs are **not** cluster-ready as written — they assume one JVM. This document is the
  design for making them so, not an implemented feature.
- No two-phase commit / distributed transaction is proposed. The pattern is
  *partition + idempotent event replay*, which is operationally far simpler and is what the
  append-only design was chosen to enable.
