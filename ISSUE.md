# ISSUE — Why these POCs exist

## Context

`daccount` today is an **account & cash management** system for VN Direct: accounts,
products, statements, groups, sessions. It does not yet own the algorithmic core of a
brokerage — the parts that decide *what a share is worth right now*, *whether a trade is
allowed*, *when a protective order fires*, and *what happens to a position when the market
or the issuer does something to it*.

Product wants to move those capabilities in-house. Before we commit to a Spring/Oracle/Kafka
implementation across four Maven modules, we need to prove that the **hard algorithmic kernel
of each capability is correct and fast enough** — in isolation, with no framework noise.

That is what this repository is: six standalone Java 21 files, one per capability, each
attacking the single part that is genuinely difficult and easy to get subtly wrong.

## The core problem

Every one of these subsystems shares the same failure mode: **they are correct 99% of the
time and catastrophically wrong in the 1% edge case — and the 1% is invisible until real
money moves.**

- A P&L number that is off by a rounding cent, multiplied across a portfolio and a trading
  day, is a reconciliation break and a support ticket.
- A stop-loss that fires on *equality* with the last tick silently never fires on a gap-down
  — the exact scenario the customer bought the stop to protect against.
- A margin check that uses `double` drifts; a forced liquidation on the wrong basis is a
  legal event.
- A settlement engine that lets pending (unsettled) cash be withdrawn is fraud waiting to
  happen.
- A 2:1 split applied without adjusting cost basis makes every downstream P&L fictional, and
  there is no way to investigate after the fact without a snapshot.

So the issue is not "can we build these features" — it is **"can we build the hard core of
each one such that the edge cases are provably handled, the money math cannot drift, and
every state change is auditable and replayable."**

## What each POC must prove

| # | POC | The single hard question it answers |
|---|-----|-------------------------------------|
| 11 | order-matching | Can we match limit/market/stop orders with correct **price-time priority** and partial fills, deterministically, at low µs/order latency? |
| 12 | realtime-pnl | Can we value a live portfolio **without recomputing everything on every tick**, and push only what changed? |
| 13 | stop-loss-engine | Can triggers fire on a **price cross over an interval** (gap-aware), not on tick equality — including trailing stops and OCO? |
| 14 | margin-engine | Can we do a correct **pre-trade affordability check**, revalue on ticks, and run the margin-call → grace → forced-liquidation workflow? |
| 15 | settlement | Can we run **T+2 dual balances** (settled vs available) with rolling reuse of pending proceeds, and never leak unsettled cash to withdrawal? |
| 16 | corporate-actions | Can we apply splits/dividends/mergers at EOD with **cost-basis adjustment, open-order adjustment, and a pre/post snapshot audit trail**? |

## Non-goals (explicitly out of scope for these POCs)

- No Spring, no Maven, no Oracle, no Kafka, no persistence. These are pure-logic kernels.
- No authentication, authorization, or API surface.
- No horizontal scaling or clustering (that concern is analyzed separately in
  [CONSISTENCY.md](CONSISTENCY.md)).
- No production-grade tax/fee/regulatory rules — where these matter, the code marks the seam
  (e.g. dividend withholding tax) and keeps the POC gross.

## Definition of done for the POC phase

1. Each file compiles and runs standalone on JDK 21 (`java FooPoc.java`).
2. Each demonstrates its hard edge case explicitly in `main()` output.
3. The money math uses integer micros end-to-end — no `double` in any value path.
4. Every state change is recorded in an append-only event/audit log.
5. The path from POC to the real `daccount` module is written down (see
   [TECHNICAL.md](TECHNICAL.md) → *How this wires into daccount*).

Once these hold, we can promote each kernel into its module with confidence that the
algorithm — the part frameworks can't give us — is already correct.
