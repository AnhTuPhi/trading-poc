import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Order Matching Engine — price-time priority with limit / market / stop orders.
 *
 * Core invariants:
 *  - Bid side sorted DESC by price; ask side sorted ASC by price.
 *  - Same price level = FIFO by arrival (price-time priority).
 *  - Resting order's price wins on a match (price improvement for the aggressor).
 *  - Trades execute atomically: both sides updated, event log appended, in a single step.
 *  - Single-threaded matching loop = deterministic, no locks on the book.
 */
public class OrderMatchingPoc {

    enum Side { BUY, SELL }
    enum Type { LIMIT, MARKET, STOP }
    enum Status { OPEN, PARTIAL, FILLED, CANCELLED }

    static final class Order {
        final long id;
        final Side side;
        final Type type;
        final String symbol;
        final long quantity;
        final long limitPrice;    // 0 for MARKET
        final long stopPrice;     // 0 for non-STOP
        final long arrivalNanos;
        long remaining;
        Status status = Status.OPEN;

        Order(long id, Side side, Type type, String symbol, long qty, long limitPrice, long stopPrice, long arrivalNanos) {
            this.id = id; this.side = side; this.type = type; this.symbol = symbol;
            this.quantity = qty; this.limitPrice = limitPrice; this.stopPrice = stopPrice;
            this.arrivalNanos = arrivalNanos; this.remaining = qty;
        }
    }

    record Trade(long id, long buyOrderId, long sellOrderId, String symbol, long quantity, long price, long nanos) {}

    sealed interface Event {
        long nanos();
        default String describe() {
            return switch (this) {
                case Accepted a  -> "ACCEPT id=" + a.orderId();
                case Rejected r  -> "REJECT id=" + r.orderId() + " reason=" + r.reason();
                case Matched m   -> "TRADE  qty=" + m.trade().quantity() + " px=" + m.trade().price()
                                    + " buyer=" + m.trade().buyOrderId() + " seller=" + m.trade().sellOrderId();
                case Triggered t -> "TRIG   id=" + t.orderId() + " (stop converted to market)";
                case Cancelled c -> "CANCEL id=" + c.orderId();
            };
        }
    }
    record Accepted(long nanos, long orderId)             implements Event {}
    record Rejected(long nanos, long orderId, String reason) implements Event {}
    record Matched(long nanos, Trade trade)               implements Event {}
    record Triggered(long nanos, long orderId)            implements Event {}
    record Cancelled(long nanos, long orderId)            implements Event {}

    static final class OrderBook {
        final String symbol;
        // NavigableMap keyed by price; ArrayDeque preserves FIFO at the price level.
        final NavigableMap<Long, ArrayDeque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());
        final NavigableMap<Long, ArrayDeque<Order>> asks = new TreeMap<>();
        final List<Order> stopBook = new ArrayList<>();
        long lastTradePrice = 0;

        OrderBook(String symbol) { this.symbol = symbol; }

        void onLimit(Order o, List<Event> log, AtomicLong tradeIdGen) {
            log.add(new Accepted(o.arrivalNanos, o.id));
            cross(o, log, tradeIdGen);
            if (o.remaining > 0) {
                var side = o.side == Side.BUY ? bids : asks;
                side.computeIfAbsent(o.limitPrice, k -> new ArrayDeque<>()).addLast(o);
            }
            checkStops(log, tradeIdGen);
        }

        void onMarket(Order o, List<Event> log, AtomicLong tradeIdGen) {
            log.add(new Accepted(o.arrivalNanos, o.id));
            // Market: cross at any price by treating limit as infinity / zero.
            cross(o, log, tradeIdGen);
            if (o.remaining > 0) {
                o.status = Status.PARTIAL; // unfilled remainder of a market order is cancelled (IOC semantics here)
                log.add(new Cancelled(System.nanoTime(), o.id));
            }
            checkStops(log, tradeIdGen);
        }

        void onStop(Order o, List<Event> log) {
            log.add(new Accepted(o.arrivalNanos, o.id));
            stopBook.add(o);
        }

        /** Cross an incoming order against the opposite book. Handles partial fills. */
        private void cross(Order incoming, List<Event> log, AtomicLong tradeIdGen) {
            var oppositeBook = incoming.side == Side.BUY ? asks : bids;
            while (incoming.remaining > 0 && !oppositeBook.isEmpty()) {
                var bestEntry = oppositeBook.firstEntry();
                long restingPrice = bestEntry.getKey();

                // Check if the aggressor wants to cross at this price.
                if (incoming.type == Type.LIMIT) {
                    boolean crosses = incoming.side == Side.BUY
                        ? incoming.limitPrice >= restingPrice
                        : incoming.limitPrice <= restingPrice;
                    if (!crosses) break;
                }

                var queue = bestEntry.getValue();
                var resting = queue.peekFirst();
                long matchQty = Math.min(incoming.remaining, resting.remaining);
                long ts = System.nanoTime();

                // Resting order's price wins (price improvement).
                var trade = (incoming.side == Side.BUY)
                    ? new Trade(tradeIdGen.incrementAndGet(), incoming.id, resting.id, symbol, matchQty, restingPrice, ts)
                    : new Trade(tradeIdGen.incrementAndGet(), resting.id, incoming.id, symbol, matchQty, restingPrice, ts);

                incoming.remaining -= matchQty;
                resting.remaining -= matchQty;
                lastTradePrice = restingPrice;
                log.add(new Matched(ts, trade));

                if (resting.remaining == 0) {
                    resting.status = Status.FILLED;
                    queue.pollFirst();
                    if (queue.isEmpty()) oppositeBook.remove(restingPrice);
                } else {
                    resting.status = Status.PARTIAL;
                }
            }
            if (incoming.remaining == 0) incoming.status = Status.FILLED;
            else if (incoming.remaining < incoming.quantity) incoming.status = Status.PARTIAL;
        }

        /** After each trade, check if any resting stops should fire. */
        private void checkStops(List<Event> log, AtomicLong tradeIdGen) {
            if (lastTradePrice == 0) return;
            var it = stopBook.iterator();
            while (it.hasNext()) {
                var stop = it.next();
                boolean triggered = stop.side == Side.BUY
                    ? lastTradePrice >= stop.stopPrice
                    : lastTradePrice <= stop.stopPrice;
                if (triggered) {
                    it.remove();
                    log.add(new Triggered(System.nanoTime(), stop.id));
                    // Convert to market order and re-enter.
                    var asMarket = new Order(stop.id, stop.side, Type.MARKET, stop.symbol,
                                             stop.remaining, 0, 0, System.nanoTime());
                    cross(asMarket, log, tradeIdGen);
                    if (asMarket.remaining > 0) log.add(new Cancelled(System.nanoTime(), stop.id));
                }
            }
        }

        void print() {
            System.out.printf("--- %s book (last=%d) ---%n", symbol, lastTradePrice);
            System.out.println("  ASKS:");
            asks.entrySet().stream().limit(5).forEach(e -> {
                long sum = e.getValue().stream().mapToLong(o -> o.remaining).sum();
                System.out.printf("    %5d @ %6d  (%d orders)%n", sum, e.getKey(), e.getValue().size());
            });
            System.out.println("  BIDS:");
            bids.entrySet().stream().limit(5).forEach(e -> {
                long sum = e.getValue().stream().mapToLong(o -> o.remaining).sum();
                System.out.printf("    %5d @ %6d  (%d orders)%n", sum, e.getKey(), e.getValue().size());
            });
        }
    }

    public static void main(String[] args) {
        var book = new OrderBook("VNM");
        var log = new ArrayList<Event>();
        var orderId = new AtomicLong();
        var tradeId = new AtomicLong();

        System.out.println("=== 1. Seed book ===");
        for (var o : new Order[] {
            new Order(orderId.incrementAndGet(), Side.SELL, Type.LIMIT, "VNM", 100, 81_000, 0, System.nanoTime()),
            new Order(orderId.incrementAndGet(), Side.SELL, Type.LIMIT, "VNM", 200, 80_500, 0, System.nanoTime()),
            new Order(orderId.incrementAndGet(), Side.SELL, Type.LIMIT, "VNM", 150, 80_500, 0, System.nanoTime()), // FIFO after the 200
            new Order(orderId.incrementAndGet(), Side.BUY,  Type.LIMIT, "VNM", 100, 80_000, 0, System.nanoTime()),
            new Order(orderId.incrementAndGet(), Side.BUY,  Type.LIMIT, "VNM", 250, 79_500, 0, System.nanoTime()),
        }) book.onLimit(o, log, tradeId);
        book.print();

        System.out.println("\n=== 2. Aggressive buy at 80,500 for 150 (partial fill of resting 200@80,500) ===");
        book.onLimit(new Order(orderId.incrementAndGet(), Side.BUY, Type.LIMIT, "VNM", 150, 80_500, 0, System.nanoTime()), log, tradeId);
        book.print();

        System.out.println("\n=== 3. Place stop-sell @ 80,000, then market sell of 100 to push price down ===");
        book.onStop(new Order(orderId.incrementAndGet(), Side.SELL, Type.STOP, "VNM", 50, 0, 80_000, System.nanoTime()), log);
        book.onMarket(new Order(orderId.incrementAndGet(), Side.SELL, Type.MARKET, "VNM", 100, 0, 0, System.nanoTime()), log, tradeId);
        book.print();

        System.out.println("\n=== 4. Trade tape (last 8) ===");
        var trades = log.stream().filter(e -> e instanceof Matched).map(e -> ((Matched) e).trade).toList();
        trades.subList(Math.max(0, trades.size() - 8), trades.size())
              .forEach(t -> System.out.printf("  trade#%d qty=%d px=%d (buy=%d, sell=%d)%n",
                  t.id(), t.quantity(), t.price(), t.buyOrderId(), t.sellOrderId()));

        System.out.println("\n=== 5. Latency test: 50,000 random limit orders ===");
        var rng = new Random(42);
        int before = log.size();
        long start = System.nanoTime();
        for (int i = 0; i < 50_000; i++) {
            var side = rng.nextBoolean() ? Side.BUY : Side.SELL;
            long px = 79_000 + rng.nextInt(3000);
            long qty = 10 + rng.nextInt(100);
            book.onLimit(new Order(orderId.incrementAndGet(), side, Type.LIMIT, "VNM", qty, px, 0, System.nanoTime()), log, tradeId);
        }
        long ns = System.nanoTime() - start;
        long newTrades = log.subList(before, log.size()).stream().filter(e -> e instanceof Matched).count();
        System.out.printf("  50,000 orders in %.1f ms — %.2f µs/order — %d trades produced%n",
            ns / 1e6, ns / 50_000.0 / 1e3, newTrades);
        book.print();
    }
}
