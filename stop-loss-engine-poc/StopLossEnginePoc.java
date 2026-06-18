import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Stop-loss / take-profit / trailing-stop / OCO trigger engine.
 *
 * The hard parts:
 *  1. Gaps: between two consecutive ticks the price may jump straight through a trigger
 *     (overnight gap, lunch reopen, a single fast print). The engine must fire on the
 *     CROSS over an interval, not on equality with the latest tick.
 *  2. Trailing stops: the trigger price chases favorable price moves but never gives ground.
 *  3. OCO (one-cancels-other): when one leg fires, the linked leg is cancelled.
 *
 * Data structure: per symbol we keep two sorted indexes — one for "fire when price >= X"
 * (long take-profit, short stop, buy-stop), one for "fire when price <= X" (long stop,
 * short take-profit, sell-stop). Each tick is two range queries: O(log n + k) where k is
 * the number of triggers that fired.
 */
public class StopLossEnginePoc {

    enum Direction {
        RISES_TO,   // fire when price climbs to/through trigger
        FALLS_TO    // fire when price drops to/through trigger
    }

    static final class Trigger {
        final long id;
        final String symbol;
        final long quantity;
        final boolean sellOnFire;
        long triggerPrice;
        final Direction direction;
        final long trailDistance;   // 0 if not a trailing stop
        long extremePrice;          // for trailing: highest seen (sell-stop) or lowest seen (buy-stop)
        Long ocoLinkedId;           // null if no link
        boolean active = true;
        String firedReason;

        Trigger(long id, String symbol, long qty, boolean sellOnFire, long trigger,
                Direction dir, long trail, long initialExtreme, Long ocoLinkedId) {
            this.id = id; this.symbol = symbol; this.quantity = qty; this.sellOnFire = sellOnFire;
            this.triggerPrice = trigger; this.direction = dir; this.trailDistance = trail;
            this.extremePrice = initialExtreme; this.ocoLinkedId = ocoLinkedId;
        }

        boolean isTrailing() { return trailDistance > 0; }
    }

    /** Per-symbol index. Two TreeMaps so we can do range queries on each direction. */
    static final class SymbolIndex {
        // RISES_TO: trigger fires when last < trigger <= now → query (last, now]
        final NavigableMap<Long, List<Trigger>> risesTo = new TreeMap<>();
        // FALLS_TO: trigger fires when now <= trigger < last → query [now, last)
        final NavigableMap<Long, List<Trigger>> fallsTo = new TreeMap<>();
        final Map<Long, Trigger> byId = new HashMap<>();
        long lastPrice = -1;

        void add(Trigger t) {
            byId.put(t.id, t);
            sideFor(t.direction).computeIfAbsent(t.triggerPrice, k -> new ArrayList<>()).add(t);
        }

        void remove(Trigger t) {
            byId.remove(t.id);
            var bucket = sideFor(t.direction).get(t.triggerPrice);
            if (bucket != null) {
                bucket.remove(t);
                if (bucket.isEmpty()) sideFor(t.direction).remove(t.triggerPrice);
            }
        }

        NavigableMap<Long, List<Trigger>> sideFor(Direction d) {
            return d == Direction.RISES_TO ? risesTo : fallsTo;
        }
    }

    record FireEvent(long nanos, long triggerId, String symbol, long qty,
                     boolean sell, long priceAtFire, String reason) {}

    static final class Engine {
        final Map<String, SymbolIndex> bySymbol = new HashMap<>();
        final List<FireEvent> fired = new ArrayList<>();
        final AtomicLong idGen = new AtomicLong();

        long placeStopLoss(String symbol, long qty, long stopPrice) {
            long id = idGen.incrementAndGet();
            bySymbol.computeIfAbsent(symbol, k -> new SymbolIndex())
                    .add(new Trigger(id, symbol, qty, true, stopPrice, Direction.FALLS_TO, 0, 0, null));
            return id;
        }

        long placeTakeProfit(String symbol, long qty, long targetPrice) {
            long id = idGen.incrementAndGet();
            bySymbol.computeIfAbsent(symbol, k -> new SymbolIndex())
                    .add(new Trigger(id, symbol, qty, true, targetPrice, Direction.RISES_TO, 0, 0, null));
            return id;
        }

        long placeTrailingStop(String symbol, long qty, long currentPrice, long trailDistance) {
            long id = idGen.incrementAndGet();
            long trigger = currentPrice - trailDistance;
            bySymbol.computeIfAbsent(symbol, k -> new SymbolIndex())
                    .add(new Trigger(id, symbol, qty, true, trigger, Direction.FALLS_TO,
                                     trailDistance, currentPrice, null));
            return id;
        }

        /** Place an OCO pair: stop-loss + take-profit. Either firing cancels the other. */
        long[] placeOco(String symbol, long qty, long stopPrice, long takeProfit) {
            long stopId = idGen.incrementAndGet();
            long tpId   = idGen.incrementAndGet();
            var idx = bySymbol.computeIfAbsent(symbol, k -> new SymbolIndex());
            idx.add(new Trigger(stopId, symbol, qty, true, stopPrice, Direction.FALLS_TO, 0, 0, tpId));
            idx.add(new Trigger(tpId,   symbol, qty, true, takeProfit, Direction.RISES_TO, 0, 0, stopId));
            return new long[]{stopId, tpId};
        }

        /** Drive the engine with a tick. May fire 0..N triggers. */
        void onTick(String symbol, long price) {
            var idx = bySymbol.get(symbol);
            if (idx == null) return;
            long last = idx.lastPrice;
            idx.lastPrice = price;
            if (last < 0) return; // first tick — establish baseline only

            // 1. Move trailing stops first — they ratchet on favorable moves.
            for (var t : idx.byId.values()) {
                if (!t.active || !t.isTrailing()) continue;
                if (price > t.extremePrice) {
                    long oldTrigger = t.triggerPrice;
                    t.extremePrice = price;
                    long newTrigger = price - t.trailDistance;
                    if (newTrigger > t.triggerPrice) {
                        idx.sideFor(t.direction).get(oldTrigger).remove(t);
                        if (idx.sideFor(t.direction).get(oldTrigger).isEmpty())
                            idx.sideFor(t.direction).remove(oldTrigger);
                        t.triggerPrice = newTrigger;
                        idx.sideFor(t.direction).computeIfAbsent(newTrigger, k -> new ArrayList<>()).add(t);
                    }
                }
            }

            // 2. RISES_TO: fired when triggerPrice ∈ (last, price] — works whether last < price OR
            //    the price gapped up over it (gap = wide range).
            long lo = Math.min(last, price), hi = Math.max(last, price);
            if (price >= last) {
                // include both ends of the cross: trigger == price still fires.
                fireRange(idx, Direction.RISES_TO, last, price, false, true, "RISES_TO crossed " + lo + "→" + hi);
            }
            // 3. FALLS_TO: trigger ∈ [price, last) when price <= last; also handle gap-downs.
            if (price <= last) {
                fireRange(idx, Direction.FALLS_TO, price, last, true, false, "FALLS_TO crossed " + hi + "→" + lo);
            }
        }

        private void fireRange(SymbolIndex idx, Direction dir, long lowKey, long highKey,
                               boolean lowInclusive, boolean highInclusive, String reasonBase) {
            var view = idx.sideFor(dir).subMap(lowKey, lowInclusive, highKey, highInclusive);
            if (view.isEmpty()) return;
            // Copy keys to avoid ConcurrentModification when we remove.
            var keys = new ArrayList<>(view.keySet());
            for (long k : keys) {
                var bucket = idx.sideFor(dir).get(k);
                if (bucket == null) continue;
                var copy = new ArrayList<>(bucket);
                for (var t : copy) {
                    if (!t.active) continue;
                    t.active = false;
                    t.firedReason = reasonBase + " (trigger=" + t.triggerPrice + ")";
                    idx.remove(t);
                    fired.add(new FireEvent(System.nanoTime(), t.id, t.symbol, t.quantity,
                                            t.sellOnFire, idx.lastPrice, t.firedReason));
                    // OCO: cancel the linked leg if it's still active.
                    if (t.ocoLinkedId != null) {
                        var other = idx.byId.get(t.ocoLinkedId);
                        if (other != null && other.active) {
                            other.active = false;
                            idx.remove(other);
                        }
                    }
                }
            }
        }
    }

    public static void main(String[] args) {
        var engine = new Engine();

        // Scenario 1: simple stop-loss on VNM @ 80,000.
        long stop1 = engine.placeStopLoss("VNM", 100, 80_000);
        System.out.println("Placed stop-loss #" + stop1 + " on VNM @ 80,000 qty 100");
        engine.onTick("VNM", 82_000);  // baseline
        engine.onTick("VNM", 80_500);  // approaching
        engine.onTick("VNM", 80_001);  // just above
        engine.onTick("VNM", 79_800);  // CROSS — must fire even though no tick was exactly at 80,000

        // Scenario 2: a GAP-DOWN that jumps over the stop entirely.
        long stop2 = engine.placeStopLoss("FPT", 50, 120_000);
        System.out.println("Placed stop-loss #" + stop2 + " on FPT @ 120,000 qty 50");
        engine.onTick("FPT", 122_000);
        engine.onTick("FPT", 115_000);  // overnight gap clean through 120,000 — must still fire

        // Scenario 3: take-profit (rises-to).
        long tp1 = engine.placeTakeProfit("HPG", 200, 30_000);
        engine.onTick("HPG", 28_500);
        engine.onTick("HPG", 29_800);
        engine.onTick("HPG", 30_500);  // crosses up through 30,000 — fires

        // Scenario 4: trailing stop. Buy was at 100k, trail 5k.
        long trail = engine.placeTrailingStop("VIC", 80, 100_000, 5_000);
        System.out.println("Placed trailing-stop #" + trail + " on VIC @ 95,000 (trail 5,000)");
        engine.onTick("VIC", 100_000);  // baseline
        engine.onTick("VIC", 103_000);  // ratchets to 98,000
        engine.onTick("VIC", 108_000);  // ratchets to 103,000
        engine.onTick("VIC", 106_000);  // no change to trigger (drop, not new high)
        engine.onTick("VIC", 102_500);  // CROSS the 103,000 trigger — fires

        // Scenario 5: OCO — stop @ 75k, take-profit @ 90k. Price runs to 91k — TP fires, stop cancelled.
        long[] oco = engine.placeOco("MWG", 30, 75_000, 90_000);
        System.out.println("Placed OCO #" + oco[0] + "/#" + oco[1] + " on MWG (stop 75k / tp 90k)");
        engine.onTick("MWG", 82_000);
        engine.onTick("MWG", 91_000);   // TP fires; stop is cancelled by OCO link.
        engine.onTick("MWG", 70_000);   // no fire — both legs already inactive.

        System.out.println("\n=== Fire events ===");
        for (var f : engine.fired) {
            System.out.printf("  trigger#%d %s qty=%d at px=%d  [%s]%n",
                f.triggerId(), f.sell() ? "SELL" : "BUY", f.qty(), f.priceAtFire(), f.reason());
        }
        System.out.println("\nTotal fires: " + engine.fired.size());
        System.out.println("Still active triggers: " + engine.bySymbol.values().stream()
            .flatMap(i -> i.byId.values().stream()).filter(t -> t.active).count());
    }
}
