import java.time.*;
import java.util.*;

/**
 * Corporate actions — splits, dividends (cash + stock), mergers.
 *
 * Principles:
 *  - Actions live in a queue keyed by effective date. They are NOT applied at the moment they
 *    are announced — they fire on the EOD pass for that date.
 *  - We take a SNAPSHOT of positions and open orders before applying, and a snapshot after.
 *    Both snapshots go into an immutable audit log. This is non-negotiable: corporate-action
 *    bugs are silent and irreversible without the snapshot trail.
 *  - Cost basis adjusts so that historical P&L isn't fictional. (A 2:1 split doesn't make you
 *    50% richer — your basis halves with your share count.)
 *  - Open orders adjust along with positions: a buy-limit at ₫160k after a 2:1 split becomes
 *    a buy-limit at ₫80k.
 *  - Historical positions are never deleted; we append adjustment events on top.
 */
public class CorporateActionsPoc {

    record Position(String symbol, long qty, long avgCostMicros) {
        Position withQty(long q)         { return new Position(symbol, q, avgCostMicros); }
        Position withCost(long c)        { return new Position(symbol, qty, c); }
    }

    record OpenOrder(long id, String symbol, boolean buy, long qty, long limitMicros) {
        OpenOrder withQty(long q)        { return new OpenOrder(id, symbol, buy, q, limitMicros); }
        OpenOrder withLimit(long p)      { return new OpenOrder(id, symbol, buy, qty, p); }
    }

    sealed interface CorporateAction {
        String symbol();
        LocalDate effective();
        String describe();
    }
    record Split(String symbol, LocalDate effective, long numerator, long denominator) implements CorporateAction {
        public String describe() { return numerator + ":" + denominator + " split"; }
    }
    record CashDividend(String symbol, LocalDate effective, long perShareMicros) implements CorporateAction {
        public String describe() { return "cash dividend " + perShareMicros + "/share"; }
    }
    record StockDividend(String symbol, LocalDate effective, double ratio) implements CorporateAction {
        public String describe() { return "stock dividend " + (ratio * 100) + "%"; }
    }
    record Merger(String symbol, LocalDate effective, String acquirer, double exchangeRatio, long cashPerShareMicros) implements CorporateAction {
        public String describe() {
            return "merger into " + acquirer + " at ratio " + exchangeRatio
                + " + cash " + cashPerShareMicros + "/share";
        }
    }

    record PortfolioSnapshot(
        LocalDate at,
        Map<String, Position> positions,
        List<OpenOrder> openOrders,
        long cashMicros
    ) {
        static PortfolioSnapshot of(LocalDate d, Portfolio p) {
            return new PortfolioSnapshot(d,
                Map.copyOf(p.positions),
                List.copyOf(p.openOrders),
                p.cashMicros);
        }
    }

    record AdjustmentEvent(LocalDate at, CorporateAction action,
                           PortfolioSnapshot before, PortfolioSnapshot after,
                           String notes) {}

    static final class Portfolio {
        final Map<String, Position> positions = new HashMap<>();
        final List<OpenOrder> openOrders = new ArrayList<>();
        long cashMicros;
        final List<AdjustmentEvent> history = new ArrayList<>();
    }

    static final class Engine {
        final NavigableMap<LocalDate, List<CorporateAction>> queue = new TreeMap<>();

        void schedule(CorporateAction a) {
            queue.computeIfAbsent(a.effective(), k -> new ArrayList<>()).add(a);
        }

        /** EOD pass — apply every queued action whose effective date is <= today. */
        void runEod(Portfolio p, LocalDate today) {
            var due = queue.headMap(today, true);
            for (var entry : new ArrayList<>(due.entrySet())) {
                for (var a : entry.getValue()) apply(p, a);
                queue.remove(entry.getKey());
            }
        }

        private void apply(Portfolio p, CorporateAction a) {
            var before = PortfolioSnapshot.of(a.effective(), p);
            var notes = new StringBuilder();
            switch (a) {
                case Split s          -> applySplit(p, s, notes);
                case CashDividend cd  -> applyCashDividend(p, cd, notes);
                case StockDividend sd -> applyStockDividend(p, sd, notes);
                case Merger m         -> applyMerger(p, m, notes);
            }
            var after = PortfolioSnapshot.of(a.effective(), p);
            p.history.add(new AdjustmentEvent(a.effective(), a, before, after, notes.toString().trim()));
        }

        private void applySplit(Portfolio p, Split s, StringBuilder notes) {
            var pos = p.positions.get(s.symbol());
            if (pos != null) {
                long newQty  = pos.qty() * s.numerator() / s.denominator();
                long newCost = pos.avgCostMicros() * s.denominator() / s.numerator();
                p.positions.put(s.symbol(), new Position(s.symbol(), newQty, newCost));
                notes.append("position: ").append(pos.qty()).append("@").append(pos.avgCostMicros())
                     .append(" → ").append(newQty).append("@").append(newCost).append('\n');
            }
            // Adjust open orders symmetrically.
            p.openOrders.replaceAll(o -> {
                if (!o.symbol().equals(s.symbol())) return o;
                long newQty  = o.qty() * s.numerator() / s.denominator();
                long newLim  = o.limitMicros() * s.denominator() / s.numerator();
                notes.append("order#").append(o.id()).append(": ").append(o.qty()).append("@").append(o.limitMicros())
                     .append(" → ").append(newQty).append("@").append(newLim).append('\n');
                return o.withQty(newQty).withLimit(newLim);
            });
        }

        private void applyCashDividend(Portfolio p, CashDividend cd, StringBuilder notes) {
            var pos = p.positions.get(cd.symbol());
            if (pos == null) return;
            long cash = pos.qty() * cd.perShareMicros();
            p.cashMicros += cash;
            notes.append("cash +").append(cash).append(" (").append(pos.qty()).append(" × ")
                 .append(cd.perShareMicros()).append(")\n");
            // Note: in Vietnam, dividend WHT applies — we'd subtract tax here. POC keeps it gross.
        }

        private void applyStockDividend(Portfolio p, StockDividend sd, StringBuilder notes) {
            var pos = p.positions.get(sd.symbol());
            if (pos == null) return;
            long extra = (long) (pos.qty() * sd.ratio());
            long newQty = pos.qty() + extra;
            // New basis: same total cost, spread over more shares.
            long totalCost = pos.qty() * pos.avgCostMicros();
            long newCost = totalCost / newQty;
            p.positions.put(sd.symbol(), new Position(sd.symbol(), newQty, newCost));
            notes.append("position: ").append(pos.qty()).append("@").append(pos.avgCostMicros())
                 .append(" → ").append(newQty).append("@").append(newCost).append('\n');
        }

        private void applyMerger(Portfolio p, Merger m, StringBuilder notes) {
            var pos = p.positions.get(m.symbol());
            if (pos == null) return;
            long newQty = (long) (pos.qty() * m.exchangeRatio());
            long cash   = pos.qty() * m.cashPerShareMicros();
            // Cost basis of acquirer shares = original total basis − cash portion.
            long totalCost = pos.qty() * pos.avgCostMicros();
            long acquirerCost = newQty == 0 ? 0 : Math.max(0, totalCost - cash) / newQty;
            p.positions.remove(m.symbol());
            if (newQty > 0) {
                p.positions.merge(m.acquirer(),
                    new Position(m.acquirer(), newQty, acquirerCost),
                    (existing, fresh) -> {
                        long combinedQty = existing.qty() + fresh.qty();
                        long combinedCost = (existing.qty() * existing.avgCostMicros()
                                           + fresh.qty()    * fresh.avgCostMicros()) / combinedQty;
                        return new Position(m.acquirer(), combinedQty, combinedCost);
                    });
            }
            p.cashMicros += cash;
            notes.append("position ").append(m.symbol()).append(" ").append(pos.qty()).append("@")
                 .append(pos.avgCostMicros()).append(" → ").append(newQty).append(" ")
                 .append(m.acquirer()).append("@").append(acquirerCost).append(" + cash ").append(cash);
            // Open orders on the original symbol must be cancelled — they don't transfer.
            p.openOrders.removeIf(o -> {
                if (o.symbol().equals(m.symbol())) {
                    notes.append("\norder#").append(o.id()).append(" cancelled (symbol delisted)");
                    return true;
                }
                return false;
            });
        }
    }

    static void show(Portfolio p, String tag) {
        System.out.printf("--- %s ---%n", tag);
        System.out.println("  cash: " + p.cashMicros);
        p.positions.values().forEach(pos ->
            System.out.printf("  %-4s qty=%d avgCost=%d%n", pos.symbol(), pos.qty(), pos.avgCostMicros()));
        p.openOrders.forEach(o ->
            System.out.printf("  order#%d %s %d %s @ %d%n",
                o.id(), o.buy() ? "BUY" : "SELL", o.qty(), o.symbol(), o.limitMicros()));
    }

    public static void main(String[] args) {
        var pf = new Portfolio();
        pf.cashMicros = 10_000_000_000L;
        pf.positions.put("VNM", new Position("VNM", 100, 160_000_000_000L));
        pf.positions.put("FPT", new Position("FPT", 500, 100_000_000_000L));
        pf.positions.put("HPG", new Position("HPG", 1000, 30_000_000_000L));
        pf.positions.put("BCM", new Position("BCM", 200, 70_000_000_000L));
        pf.openOrders.add(new OpenOrder(1, "VNM", true, 50, 158_000_000_000L));
        pf.openOrders.add(new OpenOrder(2, "BCM", false, 100, 75_000_000_000L));

        show(pf, "Day 0");

        var engine = new Engine();
        engine.schedule(new Split("VNM", LocalDate.of(2026, 6, 18), 2, 1));               // 2:1 split
        engine.schedule(new CashDividend("FPT", LocalDate.of(2026, 6, 18), 2_000_000_000L));// ₫2k cash dividend
        engine.schedule(new StockDividend("HPG", LocalDate.of(2026, 6, 19), 0.10));        // 10% stock dividend
        engine.schedule(new Merger("BCM", LocalDate.of(2026, 6, 20), "VHM", 0.8,
                                   50_000_000_000L));                                     // merger: 1 BCM → 0.8 VHM + ₫50k

        System.out.println("\n=== Run EOD for 2026-06-18 (split + cash div) ===");
        engine.runEod(pf, LocalDate.of(2026, 6, 18));
        show(pf, "Day 1");

        System.out.println("\n=== Run EOD for 2026-06-19 (stock div) ===");
        engine.runEod(pf, LocalDate.of(2026, 6, 19));
        show(pf, "Day 2");

        System.out.println("\n=== Run EOD for 2026-06-20 (merger) ===");
        engine.runEod(pf, LocalDate.of(2026, 6, 20));
        show(pf, "Day 3");

        System.out.println("\n=== Adjustment audit trail ===");
        for (var e : pf.history) {
            System.out.printf("%n[%s] %s (%s)%n", e.at(), e.action().describe(), e.action().symbol());
            System.out.println("  notes:");
            for (var line : e.notes().split("\n"))
                System.out.println("    " + line);
        }
    }
}
