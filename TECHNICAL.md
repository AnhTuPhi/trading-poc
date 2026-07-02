# TECHNICAL.md — The hard part of each POC

This document goes one level below the [README](README.md). For each POC it answers five
questions in the same order:

1. **The hard problem** — the one thing that is genuinely difficult and easy to get wrong.
2. **What we are protecting** — the invariant or the money/legal exposure at stake.
3. **Solution shape** — the data structures and control flow, at a glance.
4. **Key tech by responsibility** — which technique owns which sub-problem.
5. **How it solves each sub-problem** — the mechanics.
6. **Tech debt to acknowledge** — what the POC deliberately does *not* do.

Shared design decisions (money-in-micros, sealed events, single-writer, append-only logs)
are described once at the [end](#cross-cutting-decisions).

---

## 11 · Order Matching — [`OrderMatchingPoc.java`](order-matching-poc/OrderMatchingPoc.java)

### The hard problem
Match incoming orders against a resting book with **correct price-time priority** and correct
partial fills, deterministically, while a stop order can convert to a market order *mid-match*
and re-enter the same book. Get the ordering wrong and you fill the wrong customer first — a
fairness and regulatory issue.

### What we are protecting
- **Price-time priority**: best price first; within a price, first-arrived first.
- **Price improvement for the aggressor**: the resting order's price wins on a match.
- **Atomicity**: both sides updated + event logged as one indivisible step.
- **Determinism**: same input sequence ⇒ same fills, always (required for replay & testing).

### Solution shape
```
bids: TreeMap<price↓, ArrayDeque<Order>>   // best bid first, FIFO within level
asks: TreeMap<price↑, ArrayDeque<Order>>   // best ask first, FIFO within level
stopBook: List<Order>                      // resting stops, checked after every trade
```
Incoming order → `cross()` against the opposite book until it can't → rest the remainder →
`checkStops()`.

### Key tech by responsibility
| Sub-problem | Technique |
|-------------|-----------|
| Best-price lookup | `TreeMap` with reversed comparator on bids |
| FIFO within a price level | `ArrayDeque` per price key |
| Partial fills | `Math.min(incoming.remaining, resting.remaining)` loop |
| Stop → market conversion | rebuild as a `MARKET` order and recurse into `cross()` |
| Exhaustive event handling | `sealed interface Event` + pattern-matching `switch` |
| Deterministic ordering | single-threaded matching loop, no locks |

### How it solves each sub-problem
- **Priority**: `firstEntry()` on the sorted map gives best price; `peekFirst()` on the deque
  gives the oldest order at that price. The two together *are* price-time priority.
- **Partial fill**: matched quantity is the min of the two remainings; the resting order stays
  at the head of its deque if it still has quantity, otherwise it's polled and (if the level
  empties) the price key is removed.
- **Stop conversion**: on each trade `lastTradePrice` updates; `checkStops()` re-checks every
  resting stop against the new last price, and a fired stop is re-issued as a market order that
  crosses immediately. This is the subtle bit — a stop firing can itself move the price and
  fire further stops (handled because `checkStops` runs after the conversion's `cross`).

### Tech debt to acknowledge
- Market-order remainder is treated as IOC (cancel the rest) — real venues offer FOK, GTC,
  post-only, etc. Not modelled.
- `checkStops()` is O(n) over all stops on every trade. Fine for a POC; production wants the
  same TreeMap trigger index used in POC 13.
- Single symbol, single thread. Multi-symbol = shard by symbol (see [CONSISTENCY.md](CONSISTENCY.md)).
- No self-trade prevention, no fees, no tick-size/lot-size validation.

---

## 12 · Real-time P&L — [`RealtimePnlPoc.java`](realtime-pnl-poc/RealtimePnlPoc.java)

### The hard problem
A live portfolio is hit by tens of thousands of ticks per second. The naïve engine recomputes
the whole portfolio on every tick — O(positions) per tick — and pushes the whole portfolio to
the client. Both are wasteful and don't scale. **Recompute only what changed; push only what
changed.**

### What we are protecting
- **Correct cost basis**: FIFO lots for realized P&L; weighted average for O(1) unrealized.
- **No lost/duplicated ticks under concurrency**: exactly one writer touches a position.
- **Bandwidth**: the client sees deltas, not full snapshots.

### Solution shape
```
Position { ArrayDeque<Lot> lots; long avgCost; long realized; long unrealized; }
Portfolio { ConcurrentHashMap<symbol,Position>; ArrayBlockingQueue<Tick> mailbox; }
pump (virtual thread): poll tick → route to one Position → publish delta iff it changed
```

### Key tech by responsibility
| Sub-problem | Technique |
|-------------|-----------|
| Realized P&L on a sell | FIFO `ArrayDeque<Lot>` — pop oldest lots |
| Unrealized P&L on a tick | O(1) `qty × (last − avgCost)` using incremental weighted avg |
| "Only recompute what ticked" | route tick to the single `Position` for its symbol |
| Single-writer safety | one virtual-thread pump drains a `BlockingQueue` mailbox |
| "Only push what changed" | compare `prev` vs `next` unrealized, publish on difference |
| Backpressure | bounded `ArrayBlockingQueue` (drop or block policy) |

### How it solves each sub-problem
- **Delta-on-tick**: a tick names one symbol; the pump looks up that one position and
  recomputes only its unrealized. The other 199 positions are untouched.
- **FIFO vs avg split**: realized P&L *must* be FIFO (which lot did you sell?), but unrealized
  only needs total qty and average cost, so we keep the weighted average incrementally and get
  unrealized in O(1) — no scan of lots per tick.
- **Single writer**: all ticks funnel into one mailbox drained by one virtual thread, so a
  position's fields are only ever mutated by that thread. No locks in the hot path; the
  `synchronized` on `Position` only guards against the fill path (a different thread).
- **Push-on-change**: if the new unrealized equals the old (tiny/no-op tick), nothing is
  pushed — measurable bandwidth saving on a quiet book.

### Tech debt to acknowledge
- Incremental weighted average can accumulate integer-division rounding across many fills;
  production reconciles against a periodic full recompute from lots.
- Mailbox drop-on-full loses ticks silently; a real feed needs sequence numbers + gap
  detection, or a blocking/coalescing policy.
- One pump per JVM. Multi-account scale = shard portfolios across pumps (see CONSISTENCY.md).
- No corporate-action hook — a split mid-session would corrupt basis until POC 16 is wired in.

---

## 13 · Stop-Loss Engine — [`StopLossEnginePoc.java`](stop-loss-engine-poc/StopLossEnginePoc.java)

### The hard problem
**Gaps.** The price between two consecutive ticks can jump straight *through* a trigger
(overnight gap, lunch reopen, one fast print). An engine that fires on `price == trigger` or
`price <= trigger` *at the latest tick only* silently misses the gap — which is the exact case
a stop exists to protect against. Triggers must fire on the **cross over the (last, now]
interval**, not on the endpoint.

### What we are protecting
- **The customer's downside protection actually works across gaps.** This is the whole point
  of a stop.
- **Trailing stops ratchet** — the trigger chases favorable moves and never gives ground.
- **OCO integrity** — when one leg fires, the linked leg is cancelled exactly once.

### Solution shape
```
per symbol:
  risesTo: TreeMap<price, List<Trigger>>   // fire when price climbs through
  fallsTo: TreeMap<price, List<Trigger>>   // fire when price drops through
onTick: ratchet trailing stops → subMap(last, now] range query on each side → fire
```

### Key tech by responsibility
| Sub-problem | Technique |
|-------------|-----------|
| Gap-aware firing | `TreeMap.subMap(lo, hi)` **range query over the interval** |
| Two firing directions | two TreeMaps: `risesTo` and `fallsTo` |
| Trailing ratchet | move the key: remove at old trigger, re-insert at `extreme − trail` |
| OCO cancel | `ocoLinkedId` pointer; cancel the other leg on fire |
| Safe removal mid-iteration | copy keys/bucket before mutating |

### How it solves each sub-problem
- **The core trick**: instead of comparing the trigger to the newest tick, we take the whole
  price move `(last, now]` and ask the TreeMap for *every* trigger whose price lies in that
  band via `subMap`. A gap from 122k straight to 115k still returns the 120k stop, because 120k
  is inside `[115k, 122k)`. This is O(log n + k) — cheap even with many resting triggers.
- **Trailing**: on a favorable tick we advance `extremePrice`, recompute `extreme − trail`, and
  if it's higher than the current trigger we *move it in the index* (remove old key, insert new
  key). On an unfavorable tick the trigger stays put — the ratchet.
- **OCO**: each leg holds the other's id. When a leg fires it deactivates and, if the linked
  leg is still active, removes it too — so a filled take-profit cancels its paired stop.

### Tech debt to acknowledge
- Single-threaded, in-memory. Trailing-stop scan is O(n) over active triggers per tick
  (only trailing ones); a huge trailing population wants a secondary index.
- No persistence — a restart loses all resting triggers. Production must reload from a store
  and re-establish `lastPrice` before accepting ticks (a cold start could otherwise mis-fire).
- Fires produce events but don't route to a matching/execution path — that's POC 11's job.
- Intraday-only gap logic; halt/auction reopen semantics not modelled.

---

## 14 · Margin Engine — [`MarginEnginePoc.java`](margin-engine-poc/MarginEnginePoc.java)

### The hard problem
Two hard things at once: (a) a **pre-trade check** must decide *before* execution whether the
account can afford a trade under initial margin *and* won't immediately breach maintenance
margin on the resulting position mix; (b) an **intraday revaluation** must run the full
margin-call lifecycle — issue → grace window → cure *or* forced liquidation — as prices move.

### What we are protecting
- **The broker's capital**: an account must never carry more risk than its equity supports.
- **Fair, correct liquidation**: when forced, sell the *weakest* positions, only as much as
  needed to restore the ratio with a buffer — not a blanket dump.
- **Deterministic money math** across the whole equity/loan/position calculation.

### Solution shape
```
equity  = cash + Σ(qty × last) − marginLoan
ratio   = equity / positionValue      ; call when ratio < weightedMM
preTrade: project ratio & weighted MM *including the new lot*; allow iff still ≥ MM
onTick:  reprice → evaluateMargin → NONE→ISSUED→(CURED | grace-expired→LIQUIDATED)
```

### Key tech by responsibility
| Sub-problem | Technique |
|-------------|-----------|
| Per-symbol risk | `SymbolRule(initialMargin, maintenanceMargin)` table |
| Affordability | project equity/PV/weighted-MM *with the prospective trade* before allowing |
| Call lifecycle | `enum CallState { NONE, ISSUED, CURED, LIQUIDATED }` state machine |
| Grace window | `Instant callIssuedAt` + `Duration graceWindow` compared on each tick |
| "Liquidate weakest first" | sort positions by loss %, sell until ratio ≥ MM × 1.10 |
| Allow/Reject as data | `sealed interface Decision { Allow, Reject }` |

### How it solves each sub-problem
- **Pre-trade**: it doesn't just check "enough cash for initial margin"; it computes the
  *projected* maintenance ratio assuming the new position is added (`weightedMMProjected`) and
  rejects if the account would be in a call the instant the trade fills. This catches the
  "affordable now, margin-called immediately" trap.
- **Lifecycle**: `evaluateMargin` is called on every tick. A breach from `NONE`/`CURED` issues
  a call and timestamps it. A continued breach past the grace `Duration` triggers forced
  liquidation. A recovery while `ISSUED` cures the call. The state machine makes double-issuing
  or liquidating-a-cured-account impossible.
- **Liquidation**: positions sorted by realized-loss percentage; sell worst-first, re-checking
  the ratio after each sale, stopping once a 10% buffer above MM is restored. Proceeds pay down
  the loan first.

### Tech debt to acknowledge
- `marginRatio()`, `weightedMM()` use `double` — the one deliberate `double` in the repo, for
  ratio comparison only; money movements stay integer. Production should define a fixed-point
  ratio or a tolerance band to avoid boundary flapping at `ratio ≈ MM`.
- Liquidation sells whole positions in one shot at `lastPrice`; real liquidation is a
  child-order schedule with slippage and would re-price against POC 11's book.
- No cross-margin/netting across correlated symbols; no options/derivatives Greeks.
- Grace window is wall-clock only; production also needs a cure-by-deposit path.

---

## 15 · Settlement (T+2) — [`SettlementPoc.java`](settlement-poc/SettlementPoc.java)

### The hard problem
Cash exists in **two states at once**: *settled* (actually moved at the depository,
withdrawable) and *pending* (sale proceeds that haven't cleared, reusable to buy but **not**
withdrawable). Getting the two-balance accounting wrong either blocks legitimate buys or —
worse — lets a customer withdraw money the depository hasn't delivered.

### What we are protecting
- **The hard wall on withdrawal**: only settled cash can leave the account. Ever.
- **Rolling reuse**: pending sale proceeds *can* fund new buys same-day (the standard VN
  behaviour) without being withdrawable.
- **Auditability**: every booking, settlement, failure is append-only.

### Solution shape
```
settled: long                              // withdrawable
buckets: TreeMap<settlementDate, Bucket>   // pending credits/debits keyed by T+2 date
availableForBuy      = settled + pendingCredits − pendingDebits
availableForWithdraw = settled − pendingDebits
day-tick: sweep buckets with date ≤ today into settled
```

### Key tech by responsibility
| Sub-problem | Technique |
|-------------|-----------|
| Two balances | separate `settled` scalar vs `Bucket` map — never conflated |
| T+2 date math | `Cycle.settlementOf()` skipping weekends |
| Rolling reuse | `availableForBuy` includes pending credits; buys earmark pending first |
| Withdrawal wall | `availableForWithdraw` excludes pending credits entirely |
| Chronological sweep | `TreeMap.headMap(today, true)` settles oldest-first |
| Failed delivery | `Status.FAILED` bucket — settled cash never credited |
| Audit | append-only `List<AuditEntry>` per account |

### How it solves each sub-problem
- **The two formulas are the whole design.** `availableForBuy` and `availableForWithdraw`
  differ by exactly the pending credits — buys may spend them, withdrawals may not. Everything
  else is bookkeeping around those two lines.
- **Rolling reuse**: a sale books a CREDIT into a future-dated bucket; a same-day buy is
  allowed against `availableForBuy` (which sees that pending credit) and books a DEBIT into its
  own future bucket. Neither has moved settled cash yet.
- **The day-tick**: a scheduled start-of-day job sweeps every bucket due on/before today,
  netting credits − debits into settled and marking the bucket SETTLED. This is the EOD job in
  daccount terms.
- **Failure**: if the counterparty defaults, the bucket is marked FAILED and the sweep skips
  it — settled cash is never credited, and the audit log records why.

### Tech debt to acknowledge
- No FX, no partial settlement, no netting across counterparties.
- Weekend skipping is naïve — production needs a real exchange holiday calendar.
- `Instant.now()` timestamps in the audit make replays non-deterministic; production wants an
  injected clock.
- A failed *buy* bucket (we only demo a failed sale) needs a defined unwind path.

---

## 16 · Corporate Actions — [`CorporateActionsPoc.java`](corporate-actions-poc/CorporateActionsPoc.java)

### The hard problem
Splits, dividends, and mergers rewrite positions *and* cost basis *and* open orders at once,
on an effective date, in a batch. They are **silent bug factories**: a wrong split ratio makes
every downstream P&L fictional, and it's unrecoverable *after the fact* unless you kept a
before/after snapshot. The hard part is applying them correctly **and** leaving a trail you can
investigate later.

### What we are protecting
- **Cost-basis integrity**: a 2:1 split halves basis with share count — you are not 50% richer.
- **Open-order correctness**: a buy-limit at ₫160k becomes ₫80k after a 2:1 split (and a merger
  cancels orders on the delisted symbol).
- **Irreversibility insurance**: an immutable pre/post snapshot for every action.

### Solution shape
```
queue: TreeMap<effectiveDate, List<CorporateAction>>   // announced, not yet applied
runEod(today): apply every action with date ≤ today
apply(action): snapshot BEFORE → mutate via exhaustive switch → snapshot AFTER → log event
CorporateAction = sealed { Split | CashDividend | StockDividend | Merger }
```

### Key tech by responsibility
| Sub-problem | Technique |
|-------------|-----------|
| "Apply on the date, not on announce" | `TreeMap` queue keyed by effective date, EOD drain |
| Exhaustive action handling | `sealed interface CorporateAction` + `switch` (compile-checked) |
| Basis adjustment | split scales qty↑ & cost↓; stock-div spreads same total cost |
| Open-order adjustment | `openOrders.replaceAll` with the same ratio |
| Merger symbol change | remove source position, merge into acquirer with combined basis |
| Investigability | immutable `PortfolioSnapshot` before **and** after, in an `AdjustmentEvent` |

### How it solves each sub-problem
- **Deferred application**: actions are scheduled by effective date and only fire in the EOD
  pass for that date via `headMap(today, true)`. Announcement ≠ application.
- **Basis math per action type**: a 2:1 split multiplies qty by 2 and divides cost by 2 (total
  value invariant). A stock dividend keeps total cost fixed and spreads it over more shares. A
  merger carries basis (minus the cash portion) into the acquirer's shares, combining with any
  existing acquirer position by weighted average.
- **Exhaustiveness**: because `CorporateAction` is sealed, adding a new action type (e.g. rights
  issue) is a *compile error* until every `switch` handles it — no silently-unhandled action.
- **Snapshots**: `apply()` copies the whole portfolio before and after into immutable maps/lists
  and stores both in the audit history. This is the non-negotiable part — the only way to
  reconstruct "what did the split actually do" months later.

### Tech debt to acknowledge
- Dividend **withholding tax** is skipped (marked in-code) — VN applies WHT; production must
  net it.
- Integer division in basis adjustment can leave rounding residue; production tracks the
  fractional remainder or a cash-in-lieu line for fractional shares (mergers especially).
- Snapshots are full deep copies held in memory — fine for a POC, but production needs them
  externalised (event store / object storage) and diffed, not retained whole in the heap.
- No reversal/correction flow if an action was announced with wrong terms and later amended.

---

## Cross-cutting decisions

These apply across all six POCs and are the opinions worth carrying into `daccount`.

| Decision | Why | Where it shows up |
|----------|-----|-------------------|
| **Money in `long` micros** | Avoids `double` drift *and* `BigDecimal` allocation in hot paths. VND prices are integer × 10⁶. | Every value path. The only `double` is POC 14's margin *ratio* comparison. |
| **Sealed interfaces + exhaustive `switch`** | Missing a case is a compile error, not a production surprise. | `Event` (11), `Decision` (14), `CorporateAction` (16). |
| **Single-writer-per-aggregate** | No locks on the book/position; deterministic; the LMAX/Akka pattern. | Matching loop (11), P&L pump on a virtual thread (12). |
| **Append-only event/audit logs** | Required for back-testing, reconciliation, and regulatory replay. Nothing is mutated in place. | All six. |
| **Snapshots wrap irreversible ops** | Some operations can't be reasoned about after the fact without a before/after. | Corporate actions (16); the pattern generalises to any batch mutation. |
| **Two-balance / dual-state accounting** | Reusable-but-not-withdrawable is a real financial state; conflating it is a fraud or usability bug. | Settlement (15); margin's settled-vs-loan (14) is the same idea. |

## How this wires into `daccount`

| POC | Target module | Shape of the integration |
|-----|---------------|--------------------------|
| order-matching | *new* | Sits alongside a broker matching service if brought in-house. |
| realtime-pnl | `daccount-service` (Position/Statement) | The tick pump is the natural extension of the streaming-event work; one pump per shard. |
| stop-loss-engine | `daccount-api` + Kafka tick stream | Controllers place triggers; a consumer drives `onTick`. |
| margin-engine | `daccount-service` on the existing `Account` aggregate | Add `MarginPolicy` + a revaluation worker. |
| settlement | `daccount-storage` cash buckets | The T+2 sweep is the EOD scheduled job. |
| corporate-actions | *new* entities in `daccount-model` | `CorporateAction` + `AdjustmentEvent`; EOD batch in `daccount-service`. |

Scaling any of these across multiple pods/VMs raises consistency questions the single-JVM POCs
sidestep. Those are analyzed in **[CONSISTENCY.md](CONSISTENCY.md)**.
