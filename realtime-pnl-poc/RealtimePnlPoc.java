import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Real-time P&L engine — exactly the daccount problem.
 *
 * Insights:
 *  - Don't recompute the portfolio every tick. Only recompute the position whose symbol ticked.
 *  - Cost basis = FIFO over lots (each buy adds a lot; each sell pops the oldest).
 *  - Push only changed positions to the client (delta channel).
 *  - All ticks funnel into a single mailbox; the position state is touched by one thread.
 *    This matches the daccount streaming-event-poc model: one writer per aggregate.
 */
public class RealtimePnlPoc {

    record Tick(String symbol, long priceMicros, long nanos) {}
    record Fill(String symbol, long qty, long priceMicros, long nanos) {} // qty > 0 buy, qty < 0 sell

    /** A buy lot — kept in FIFO order inside a position. */
    static final class Lot {
        long qty;
        final long unitCostMicros;
        Lot(long qty, long unitCostMicros) { this.qty = qty; this.unitCostMicros = unitCostMicros; }
    }

    static final class Position {
        final String symbol;
        final ArrayDeque<Lot> lots = new ArrayDeque<>();
        long totalQty;
        long avgCostMicros;          // weighted average, kept incrementally
        long lastPriceMicros;
        long realizedPnlMicros;
        long unrealizedPnlMicros;

        Position(String symbol) { this.symbol = symbol; }

        /** Apply a fill — buys add a FIFO lot; sells consume oldest lots and realize P&L. */
        synchronized void onFill(Fill f) {
            if (f.qty() > 0) {
                lots.addLast(new Lot(f.qty(), f.priceMicros()));
                // Incremental weighted average — avoid iterating all lots.
                long newQty = totalQty + f.qty();
                avgCostMicros = (totalQty * avgCostMicros + f.qty() * f.priceMicros()) / newQty;
                totalQty = newQty;
            } else {
                long toSell = -f.qty();
                if (toSell > totalQty) throw new IllegalStateException("oversell on " + symbol);
                while (toSell > 0 && !lots.isEmpty()) {
                    var lot = lots.peekFirst();
                    long take = Math.min(toSell, lot.qty);
                    realizedPnlMicros += take * (f.priceMicros() - lot.unitCostMicros);
                    lot.qty -= take;
                    toSell -= take;
                    if (lot.qty == 0) lots.pollFirst();
                }
                totalQty += f.qty(); // f.qty is negative
                if (totalQty == 0) avgCostMicros = 0;
            }
            recomputeUnrealized();
        }

        synchronized void onTick(Tick t) {
            this.lastPriceMicros = t.priceMicros();
            recomputeUnrealized();
        }

        private void recomputeUnrealized() {
            // O(1) using avg cost — no scan over lots needed for unrealized.
            unrealizedPnlMicros = totalQty * (lastPriceMicros - avgCostMicros);
        }

        long totalPnlMicros() { return realizedPnlMicros + unrealizedPnlMicros; }
    }

    /**
     * Portfolio + tick pump.
     * The pump consumes ticks from a single queue and routes them to one position each.
     * After processing a tick, it publishes a delta only for the touched symbol.
     */
    static final class Portfolio {
        final Map<String, Position> positions = new ConcurrentHashMap<>();
        final BlockingQueue<Tick> mailbox = new ArrayBlockingQueue<>(8192);
        final AtomicLong ticksProcessed = new AtomicLong();
        final AtomicLong deltasPublished = new AtomicLong();
        volatile boolean running = true;

        void apply(Fill f) {
            positions.computeIfAbsent(f.symbol(), Position::new).onFill(f);
        }

        void offerTick(Tick t) {
            // Backpressure-safe: drop if mailbox full (or block, depending on policy).
            mailbox.offer(t);
        }

        Thread startPump(java.util.function.Consumer<Position> sink) {
            // Virtual thread is cheap; one writer per portfolio keeps the position state lock-free.
            return Thread.ofVirtual().name("pnl-pump").start(() -> {
                while (running || !mailbox.isEmpty()) {
                    try {
                        var t = mailbox.poll(100, TimeUnit.MILLISECONDS);
                        if (t == null) continue;
                        var pos = positions.get(t.symbol());
                        if (pos == null) continue; // not held — skip
                        long prev, next;
                        synchronized (pos) {
                            prev = pos.unrealizedPnlMicros;
                            pos.onTick(t);
                            next = pos.unrealizedPnlMicros;
                        }
                        ticksProcessed.incrementAndGet();
                        // Only publish when the delta is non-zero. Saves bandwidth on tiny ticks.
                        if (next != prev) {
                            sink.accept(pos);
                            deltasPublished.incrementAndGet();
                        }
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            });
        }

        void stop() { running = false; }
    }

    static String fmt(long micros) {
        boolean neg = micros < 0;
        long abs = Math.abs(micros);
        return (neg ? "-" : "") + (abs / 1_000_000) + "." + String.format("%06d", abs % 1_000_000);
    }

    public static void main(String[] args) throws Exception {
        var pf = new Portfolio();

        // User holds 100 VNM, 50 FPT, 200 HPG — bought at varying prices.
        pf.apply(new Fill("VNM", 60,  82_000_000_000L, System.nanoTime()));
        pf.apply(new Fill("VNM", 40,  85_000_000_000L, System.nanoTime()));
        pf.apply(new Fill("FPT", 50, 120_000_000_000L, System.nanoTime()));
        pf.apply(new Fill("HPG", 200, 28_500_000_000L, System.nanoTime()));

        System.out.println("=== Initial positions (cost basis only) ===");
        pf.positions.values().forEach(p ->
            System.out.printf("  %-4s qty=%d avgCost=%s%n", p.symbol, p.totalQty, fmt(p.avgCostMicros)));

        // Start the pump. The sink simulates a WebSocket push.
        // In a real app this would be a WebSocket frame. We sample 1-in-5000 so the console doesn't drown.
        var sampleCounter = new AtomicLong();
        var pump = pf.startPump(p -> {
            if (sampleCounter.incrementAndGet() % 5000 == 1) {
                System.out.printf("  -> push %s: last=%s unrealized=%s realized=%s%n",
                    p.symbol, fmt(p.lastPriceMicros), fmt(p.unrealizedPnlMicros), fmt(p.realizedPnlMicros));
            }
        });

        System.out.println("\n=== Streaming 30,000 ticks across 3 symbols ===");
        var rng = new Random(7);
        String[] symbols = {"VNM", "FPT", "HPG"};
        long[] mid = {83_000_000_000L, 122_000_000_000L, 29_000_000_000L};
        long start = System.nanoTime();
        for (int i = 0; i < 30_000; i++) {
            int s = rng.nextInt(symbols.length);
            mid[s] += (rng.nextInt(2001) - 1000) * 1_000L; // walk ±1.0 in price
            pf.offerTick(new Tick(symbols[s], mid[s], System.nanoTime()));
        }

        // Mid-stream sell: realize some VNM P&L
        Thread.sleep(50);
        pf.apply(new Fill("VNM", -30, mid[0], System.nanoTime()));
        System.out.println("\n  [executed sell of 30 VNM at " + fmt(mid[0]) + "]");

        pf.stop();
        pump.join();
        long ns = System.nanoTime() - start;

        System.out.println("\n=== Final portfolio ===");
        long total = 0;
        for (var p : pf.positions.values()) {
            total += p.totalPnlMicros();
            System.out.printf("  %-4s qty=%-4d avgCost=%-14s last=%-14s realized=%-14s unrealized=%-14s%n",
                p.symbol, p.totalQty, fmt(p.avgCostMicros), fmt(p.lastPriceMicros),
                fmt(p.realizedPnlMicros), fmt(p.unrealizedPnlMicros));
        }
        System.out.printf("  TOTAL P&L: %s%n", fmt(total));

        System.out.printf("%n=== Throughput ===%n  ticks=%d in %.1f ms — %.0f ticks/sec — deltas pushed=%d%n",
            pf.ticksProcessed.get(), ns / 1e6,
            pf.ticksProcessed.get() / (ns / 1e9),
            pf.deltasPublished.get());
    }
}
