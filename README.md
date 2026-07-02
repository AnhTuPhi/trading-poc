# Trading / Brokerage POCs

Six standalone Java 21 POCs — one focused engine each, no Spring, no Maven. Each POC is a single `.java` file you can compile and run directly. They demonstrate the core hard-part of each scenario rather than the surrounding plumbing.

## Documentation

| Doc | What's in it |
|-----|--------------|
| [ISSUE.md](ISSUE.md) | Why these POCs exist, the core problem they de-risk, and the definition of done for the POC phase. |
| [TECHNICAL.md](TECHNICAL.md) | Per-POC deep dive: the hard problem, what we protect, solution shape, key tech by responsibility, and tech debt. |
| [CONSISTENCY.md](CONSISTENCY.md) | How to scale these single-JVM engines across K8s pods / VMs without breaking their single-writer correctness. |
| [explainer.html](explainer.html) | Interactive visual walkthrough — open it in a browser to explore each engine's flow and key technique. |

## Requirements

- **JDK 21+** (your machine currently has JDK 11 — please install JDK 21).
  POCs use records, sealed interfaces, pattern-matching `switch`, and virtual threads.
- Single-file Java mode is supported: `java FooPoc.java`.

## The POCs

| # | POC | What it demonstrates |
|---|-----|----------------------|
| 11 | [order-matching-poc](order-matching-poc/OrderMatchingPoc.java) | Limit/market/stop orders, price-time priority, partial fills, stop conversion, event log, latency test (50k orders) |
| 12 | [realtime-pnl-poc](realtime-pnl-poc/RealtimePnlPoc.java) | FIFO lots + weighted-avg cost, delta-on-tick (recompute only what changed), single-writer mailbox on a virtual thread, push only changed positions |
| 13 | [stop-loss-engine-poc](stop-loss-engine-poc/StopLossEnginePoc.java) | TreeMap trigger index, **gap-aware cross detection**, trailing stops that ratchet, OCO links |
| 14 | [margin-engine-poc](margin-engine-poc/MarginEnginePoc.java) | Per-symbol IM/MM, pre-trade affordability check, post-tick revaluation, margin-call grace window, forced liquidation of weakest positions |
| 15 | [settlement-poc](settlement-poc/SettlementPoc.java) | T+2 dual balances (settled vs available), rolling-reuse of pending sale proceeds for buys, day-tick sweep, failed-bucket scenario, append-only audit |
| 16 | [corporate-actions-poc](corporate-actions-poc/CorporateActionsPoc.java) | Splits, cash & stock dividends, mergers — queued by effective date, applied at EOD with **pre/post snapshots** and open-order adjustment |

## Run

```powershell
# One POC
java D:\Claude\trading-pocs\order-matching-poc\OrderMatchingPoc.java

# All of them in sequence
D:\Claude\trading-pocs\run-all.ps1
```

## Design notes

These POCs share a few opinions worth pulling out:

- **Money in micros (integer)** — `long` micros instead of `BigDecimal` or `double`. Avoids both floating-point drift and the allocation overhead of `BigDecimal` in hot paths. Tradable in the daccount domain where prices are integer VND × 10⁶.
- **Sealed interfaces for events / actions** — exhaustive `switch` expressions catch missing branches at compile time. This shows up most in `CorporateActionsPoc.apply()` and the event logs.
- **Single-writer-per-aggregate** — the matching loop and the P&L pump each have one writer thread. No locks on the book or on a position. Trades and tick deltas funnel through a mailbox. Same pattern that makes LMAX Disruptor and Akka aggregates work.
- **Append-only event/audit logs** — every state change recorded; nothing mutated. Required for back-testing and for regulatory replay.
- **Snapshots wrap corporate actions** — splits and mergers are silent bug factories. The only way to investigate after the fact is to keep the pre/post snapshot, so we do.
- **Two-balance settlement** — settled vs available. Rolling reuse for buys, hard wall on withdrawals. Same shape the daccount cash module needs.

## How these wire up to the real `daccount` domain

| POC | Hits which part of daccount |
|-----|----------------------------|
| order-matching | new — would sit alongside the broker's matching service if one were brought in-house |
| realtime-pnl | tightly couples to `daccount-service` Position/Statement modules; the tick pump is the natural extension of `streaming-event-poc` |
| stop-loss-engine | new feature — natural fit for `daccount-api` controllers + Kafka tick stream |
| margin-engine | `Account` aggregate already exists; this would add `MarginPolicy` + a revaluation worker |
| settlement | maps almost 1:1 onto current cash-bucket logic in `daccount-storage` — the T+2 sweep is the EOD job |
| corporate-actions | needs new entities (`CorporateAction`, `AdjustmentEvent`) under `daccount-model`; EOD batch in `daccount-service` |
