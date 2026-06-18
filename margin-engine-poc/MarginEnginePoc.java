import java.util.*;
import java.time.*;

/**
 * Margin engine — pre-trade check + intraday revaluation + margin-call workflow.
 *
 * Model:
 *  - Each symbol has its own initial margin (IM) and maintenance margin (MM) requirement.
 *    IM = % of trade value you must put up at execution time.
 *    MM = % of position value your equity must cover at all times.
 *  - Equity   = cash + Σ(qty × lastPrice) − debt(margin loan)
 *  - Position value = Σ(qty × lastPrice)
 *  - Margin ratio = equity / positionValue. Call when ratio < MM.
 *  - On call: grace window for user to deposit / sell. If still bad after grace → forced liquidation
 *    of weakest positions until the ratio is back above MM with a buffer.
 *
 * All amounts in micros (1 VND = 1_000_000) to avoid floating point.
 */
public class MarginEnginePoc {

    record SymbolRule(String symbol, double initialMargin, double maintenanceMargin) {}
    record Position(String symbol, long qty, long avgCostMicros, long lastPriceMicros) {
        long valueMicros()  { return qty * lastPriceMicros; }
        long pnlMicros()    { return qty * (lastPriceMicros - avgCostMicros); }
        Position withPrice(long p) { return new Position(symbol, qty, avgCostMicros, p); }
        Position addQty(long extra, long fillPrice) {
            // Weighted-average cost basis on add.
            long newQty = qty + extra;
            long newAvg = (qty * avgCostMicros + extra * fillPrice) / newQty;
            return new Position(symbol, newQty, newAvg, lastPriceMicros);
        }
        Position reduceQty(long less) {
            return new Position(symbol, qty - less, avgCostMicros, lastPriceMicros);
        }
    }

    enum CallState { NONE, ISSUED, CURED, LIQUIDATED }

    static final class Account {
        final String id;
        long cashMicros;
        long marginLoanMicros;          // debt to broker
        final Map<String, Position> positions = new HashMap<>();
        final Map<String, SymbolRule> rules;
        CallState callState = CallState.NONE;
        Instant callIssuedAt;
        Duration graceWindow;

        Account(String id, long cashMicros, Map<String, SymbolRule> rules, Duration graceWindow) {
            this.id = id; this.cashMicros = cashMicros; this.rules = rules; this.graceWindow = graceWindow;
        }

        long positionValueMicros() {
            return positions.values().stream().mapToLong(Position::valueMicros).sum();
        }
        long equityMicros() {
            return cashMicros + positionValueMicros() - marginLoanMicros;
        }
        double marginRatio() {
            long pv = positionValueMicros();
            if (pv == 0) return Double.POSITIVE_INFINITY;
            return (double) equityMicros() / pv;
        }
        /** Weighted average MM across held positions. */
        double weightedMM() {
            long pv = positionValueMicros();
            if (pv == 0) return 0;
            double sum = 0;
            for (var p : positions.values()) {
                var r = rules.get(p.symbol());
                sum += (double) p.valueMicros() / pv * r.maintenanceMargin();
            }
            return sum;
        }

        /** Weighted MM assuming we add `extraValueMicros` worth of `addSymbol` at `addMM`. */
        double weightedMMProjected(String addSymbol, long extraValueMicros, double addMM) {
            long pv = positionValueMicros() + extraValueMicros;
            if (pv == 0) return 0;
            double sum = 0;
            for (var p : positions.values()) {
                var r = rules.get(p.symbol());
                sum += (double) p.valueMicros() / pv * r.maintenanceMargin();
            }
            sum += (double) extraValueMicros / pv * addMM;
            return sum;
        }
    }

    record TradeIntent(String symbol, long qty, long priceMicros) {} // qty > 0 = buy

    sealed interface Decision {
        record Allow(long marginUsedMicros, long cashUsedMicros, long loanDeltaMicros) implements Decision {}
        record Reject(String reason) implements Decision {}
    }

    static final class Engine {

        /** PRE-TRADE: can the account afford this trade respecting initial margin? */
        Decision preTradeCheck(Account a, TradeIntent t) {
            if (t.qty() <= 0) {
                // Selling reduces exposure: always allowed if position exists.
                var p = a.positions.get(t.symbol());
                if (p == null || p.qty() + t.qty() < 0)
                    return new Decision.Reject("oversell — not enough shares");
                return new Decision.Allow(0, 0, -t.priceMicros() * (-t.qty())); // proceeds reduce loan
            }
            var rule = a.rules.get(t.symbol());
            if (rule == null) return new Decision.Reject("symbol not in margin table: " + t.symbol());
            long tradeValue = t.qty() * t.priceMicros();
            long requiredOwn = (long) Math.ceil(tradeValue * rule.initialMargin());
            if (requiredOwn > a.cashMicros)
                return new Decision.Reject("insufficient cash for initial margin: need "
                        + requiredOwn + " have " + a.cashMicros);
            // At execution, equity is invariant: cash drops by requiredOwn, position value rises by
            // tradeValue, loan rises by (tradeValue − requiredOwn). The check is whether maintenance
            // margin still holds for the new position mix.
            long projectedPV = a.positionValueMicros() + tradeValue;
            long projectedEquity = a.equityMicros();
            double projectedRatio = (double) projectedEquity / projectedPV;
            double projectedMM = a.weightedMMProjected(t.symbol(), tradeValue, rule.maintenanceMargin());
            if (projectedRatio < projectedMM)
                return new Decision.Reject("would breach maintenance margin immediately");
            return new Decision.Allow(requiredOwn, requiredOwn, tradeValue - requiredOwn);
        }

        void applyTrade(Account a, TradeIntent t) {
            var d = preTradeCheck(a, t);
            if (d instanceof Decision.Reject r) throw new IllegalStateException("rejected: " + r.reason());

            if (t.qty() > 0) {
                // BUY: put up IM in cash, borrow the rest, position appears.
                var rule = a.rules.get(t.symbol());
                long tradeValue = t.qty() * t.priceMicros();
                long requiredOwn = (long) Math.ceil(tradeValue * rule.initialMargin());
                a.cashMicros -= requiredOwn;
                a.marginLoanMicros += tradeValue - requiredOwn;
                a.positions.merge(t.symbol(),
                    new Position(t.symbol(), t.qty(), t.priceMicros(), t.priceMicros()),
                    (old, fresh) -> old.addQty(t.qty(), t.priceMicros()));
            } else {
                // SELL: proceeds pay down loan first, remainder to cash.
                long proceeds = t.priceMicros() * (-t.qty());
                long payDown = Math.min(a.marginLoanMicros, proceeds);
                a.marginLoanMicros -= payDown;
                a.cashMicros += proceeds - payDown;
                var p = a.positions.get(t.symbol());
                var reduced = p.reduceQty(-t.qty());
                if (reduced.qty() == 0) a.positions.remove(t.symbol());
                else a.positions.put(t.symbol(), reduced);
            }
        }

        /** Mark every position to a new market price tick. May raise or cure a margin call. */
        void onTick(Account a, String symbol, long priceMicros, Instant now) {
            var p = a.positions.get(symbol);
            if (p != null) a.positions.put(symbol, p.withPrice(priceMicros));
            evaluateMargin(a, now);
        }

        void evaluateMargin(Account a, Instant now) {
            double ratio = a.marginRatio();
            double mm = a.weightedMM();
            if (ratio < mm) {
                if (a.callState == CallState.NONE || a.callState == CallState.CURED) {
                    a.callState = CallState.ISSUED;
                    a.callIssuedAt = now;
                    System.out.printf("  ! MARGIN CALL on %s at %s — ratio=%.3f MM=%.3f equity=%d pv=%d%n",
                        a.id, now, ratio, mm, a.equityMicros(), a.positionValueMicros());
                } else if (a.callState == CallState.ISSUED && Duration.between(a.callIssuedAt, now).compareTo(a.graceWindow) > 0) {
                    System.out.printf("  ! GRACE EXPIRED on %s — forced liquidation%n", a.id);
                    forcedLiquidate(a);
                    a.callState = CallState.LIQUIDATED;
                }
            } else if (a.callState == CallState.ISSUED) {
                System.out.printf("  ✓ Margin call CURED on %s — ratio %.3f >= MM %.3f%n", a.id, ratio, mm);
                a.callState = CallState.CURED;
            }
        }

        /** Sell weakest positions (largest loss %) until ratio is back above MM with buffer. */
        void forcedLiquidate(Account a) {
            var sorted = new ArrayList<>(a.positions.values());
            sorted.sort(Comparator.comparingDouble(
                p -> (double) (p.lastPriceMicros() - p.avgCostMicros()) / p.avgCostMicros()));
            for (var p : sorted) {
                if (a.marginRatio() >= a.weightedMM() * 1.10) break; // 10% buffer
                System.out.printf("    liquidating %d %s @ %d (cost basis %d)%n",
                    p.qty(), p.symbol(), p.lastPriceMicros(), p.avgCostMicros());
                applyTrade(a, new TradeIntent(p.symbol(), -p.qty(), p.lastPriceMicros()));
            }
        }
    }

    public static void main(String[] args) {
        // Amounts in plain VND. Prices and cash are whole numbers of VND.
        var rules = Map.of(
            "VNM", new SymbolRule("VNM", 0.50, 0.30),  // blue chip — easier margin
            "FPT", new SymbolRule("FPT", 0.50, 0.30),
            "SMC", new SymbolRule("SMC", 0.70, 0.50)   // small cap — stricter
        );
        var engine = new Engine();
        // Alice has ₫100M cash.
        var alice = new Account("alice", 100_000_000L, rules, Duration.ofHours(4));

        System.out.println("=== Pre-trade: Alice has ₫100M cash, buys 2000 VNM @ ₫100k (fully levered) ===");
        var intent = new TradeIntent("VNM", 2000, 100_000L); // 2000 × ₫100k = ₫200M
        var d = engine.preTradeCheck(alice, intent);
        System.out.println("  decision: " + d);
        engine.applyTrade(alice, intent);
        System.out.printf("  after trade: cash=%d loan=%d pv=%d equity=%d ratio=%.3f%n",
            alice.cashMicros, alice.marginLoanMicros, alice.positionValueMicros(),
            alice.equityMicros(), alice.marginRatio());

        System.out.println("\n=== Buy a small-cap that exceeds remaining cash ===");
        var bad = new TradeIntent("SMC", 100, 200_000L);
        System.out.println("  pre-trade check: " + engine.preTradeCheck(alice, bad));

        System.out.println("\n=== VNM drops 30% to ₫70k — margin call ===");
        engine.onTick(alice, "VNM", 70_000L, Instant.parse("2026-06-17T10:00:00Z"));

        System.out.println("\n=== 1h later, ₫68k: still below MM but inside grace ===");
        engine.onTick(alice, "VNM", 68_000L, Instant.parse("2026-06-17T11:00:00Z"));

        System.out.println("\n=== 5h later, still ₫68k: outside grace — forced liquidation ===");
        engine.onTick(alice, "VNM", 68_000L, Instant.parse("2026-06-17T15:30:00Z"));
        System.out.printf("  after liquidation: cash=%d loan=%d positions=%d ratio=%.3f%n",
            alice.cashMicros, alice.marginLoanMicros, alice.positions.size(), alice.marginRatio());

        System.out.println("\n=== Curable scenario on Bob: 1000 FPT @ ₫100k, drops then recovers ===");
        var bob = new Account("bob", 50_000_000L, rules, Duration.ofHours(4));
        engine.applyTrade(bob, new TradeIntent("FPT", 1000, 100_000L));
        engine.onTick(bob, "FPT", 70_000L, Instant.parse("2026-06-17T10:00:00Z")); // dips → call
        engine.onTick(bob, "FPT", 78_000L, Instant.parse("2026-06-17T11:00:00Z")); // recovers → cure
    }
}

