import java.time.*;
import java.util.*;

/**
 * Settlement engine — T+2 cash with rolling reuse and dual balances.
 *
 * Two balances per account:
 *   - settled:   cash that has actually moved at the depository. Withdrawable.
 *   - pending:   sale proceeds not yet settled. Reusable for buys (rolling),
 *                NOT withdrawable.
 *   - available_for_buy   = settled + pending
 *   - available_for_cash  = settled  (the user can only take this home)
 *
 * Lifecycle of a sale:
 *   T+0  trade booked      → cash goes into `pending` bucket keyed by settlementDate
 *   T+2  settlement run    → bucket sweeps into `settled`, audit entry written
 *   (failed) settlement    → bucket marked FAILED, audit logged, cash NOT credited
 *
 * Lifecycle of a buy:
 *   T+0  trade booked      → required cash earmarked from pending first, then settled
 *                            (this is the "rolling reuse")
 *   T+2  settlement run    → earmarked cash actually leaves the account
 *
 * Every state change is appended to a per-account audit log — append-only, never mutated.
 */
public class SettlementPoc {

    enum Status { PENDING, SETTLED, FAILED }
    enum Direction { CREDIT, DEBIT }   // CREDIT = inflow (sale), DEBIT = outflow (buy)

    record Movement(
        long id,
        Direction direction,
        long amountMicros,
        LocalDate tradeDate,
        LocalDate settlementDate,
        String description
    ) {
        Movement settled() { return this; } // value object; status is tracked on the bucket
    }

    /** A pending bucket = movements waiting to settle on a given date. */
    static final class Bucket {
        final LocalDate settlementDate;
        final List<Movement> credits = new ArrayList<>();
        final List<Movement> debits  = new ArrayList<>();
        Status status = Status.PENDING;
        Bucket(LocalDate settlementDate) { this.settlementDate = settlementDate; }
        long netMicros() {
            return credits.stream().mapToLong(Movement::amountMicros).sum()
                 - debits.stream().mapToLong(Movement::amountMicros).sum();
        }
    }

    record AuditEntry(Instant at, String kind, String detail) {}

    static final class Account {
        final String id;
        long settledMicros;
        // Sorted by settlement date so the day-tick can sweep oldest first.
        final NavigableMap<LocalDate, Bucket> buckets = new TreeMap<>();
        final List<AuditEntry> audit = new ArrayList<>();
        long movementIdGen;

        Account(String id, long openingCash) {
            this.id = id;
            this.settledMicros = openingCash;
            log("OPEN", "opening cash " + openingCash);
        }

        void log(String kind, String detail) {
            audit.add(new AuditEntry(Instant.now(), kind, detail));
        }

        long pendingCreditTotal() {
            return buckets.values().stream()
                .filter(b -> b.status == Status.PENDING)
                .flatMap(b -> b.credits.stream())
                .mapToLong(Movement::amountMicros).sum();
        }
        long pendingDebitTotal() {
            return buckets.values().stream()
                .filter(b -> b.status == Status.PENDING)
                .flatMap(b -> b.debits.stream())
                .mapToLong(Movement::amountMicros).sum();
        }

        long availableForBuy() {
            // settled + pending sale proceeds − pending buy debits
            return settledMicros + pendingCreditTotal() - pendingDebitTotal();
        }
        long availableForWithdraw() {
            return settledMicros - pendingDebitTotal();
        }
    }

    /** Settlement cycle policy — VN equities are T+2. */
    static final class Cycle {
        final int days;
        Cycle(int days) { this.days = days; }
        LocalDate settlementOf(LocalDate tradeDate) {
            // Skip weekends — naive but representative.
            LocalDate d = tradeDate;
            int added = 0;
            while (added < days) {
                d = d.plusDays(1);
                var dow = d.getDayOfWeek();
                if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) added++;
            }
            return d;
        }
    }

    static final class Engine {
        final Cycle cycle;
        Engine(Cycle cycle) { this.cycle = cycle; }

        /** Book a SELL — credit to a future bucket. */
        void bookSale(Account a, long amount, LocalDate tradeDate) {
            var sd = cycle.settlementOf(tradeDate);
            var m = new Movement(++a.movementIdGen, Direction.CREDIT, amount, tradeDate, sd, "SALE");
            a.buckets.computeIfAbsent(sd, Bucket::new).credits.add(m);
            a.log("BOOK_SALE", "+" + amount + " settling " + sd);
        }

        /** Book a BUY — debit a future bucket. Uses rolling reuse if available. */
        void bookBuy(Account a, long amount, LocalDate tradeDate) {
            if (amount > a.availableForBuy()) {
                a.log("REJECT_BUY", "need " + amount + " have " + a.availableForBuy());
                throw new IllegalStateException("insufficient cash for buy: need "
                    + amount + " available " + a.availableForBuy());
            }
            var sd = cycle.settlementOf(tradeDate);
            var m = new Movement(++a.movementIdGen, Direction.DEBIT, amount, tradeDate, sd, "BUY");
            a.buckets.computeIfAbsent(sd, Bucket::new).debits.add(m);
            a.log("BOOK_BUY", "-" + amount + " settling " + sd);
        }

        /** Withdrawal — only settled cash, never pending. */
        void withdraw(Account a, long amount) {
            if (amount > a.availableForWithdraw()) {
                a.log("REJECT_WD", "need " + amount + " have " + a.availableForWithdraw());
                throw new IllegalStateException("cannot withdraw " + amount
                    + " — only " + a.availableForWithdraw() + " is settled");
            }
            a.settledMicros -= amount;
            a.log("WITHDRAW", "-" + amount);
        }

        /**
         * Day-tick: sweep all buckets with settlementDate <= today into the settled balance.
         * Run by a scheduled job at start-of-day (after the depository confirms).
         */
        void onSettlementDay(Account a, LocalDate today) {
            var due = a.buckets.headMap(today, true);
            for (var entry : new ArrayList<>(due.entrySet())) {
                var b = entry.getValue();
                if (b.status != Status.PENDING) continue;
                long net = b.netMicros();
                a.settledMicros += net;
                b.status = Status.SETTLED;
                a.log("SETTLE",
                    "bucket " + b.settlementDate + " net=" + net
                    + " (" + b.credits.size() + " credits, " + b.debits.size() + " debits)");
            }
        }

        /** Counterparty fails to deliver — credit bucket marked FAILED, settled NOT touched. */
        void failBucket(Account a, LocalDate settlementDate, String reason) {
            var b = a.buckets.get(settlementDate);
            if (b == null) throw new IllegalStateException("no bucket on " + settlementDate);
            b.status = Status.FAILED;
            a.log("FAIL", "bucket " + settlementDate + " reason=" + reason);
        }
    }

    static void show(Account a, String tag) {
        System.out.printf("  %-12s settled=%s pending_cr=%s pending_db=%s avail_buy=%s avail_wd=%s%n",
            tag, a.settledMicros, a.pendingCreditTotal(), a.pendingDebitTotal(),
            a.availableForBuy(), a.availableForWithdraw());
    }

    public static void main(String[] args) {
        var engine = new Engine(new Cycle(2)); // T+2
        var alice = new Account("alice", 100_000_000L); // ₫100M opening

        LocalDate mon = LocalDate.of(2026, 6, 15);
        LocalDate tue = LocalDate.of(2026, 6, 16);
        LocalDate wed = LocalDate.of(2026, 6, 17);
        LocalDate thu = LocalDate.of(2026, 6, 18);
        LocalDate fri = LocalDate.of(2026, 6, 19);
        LocalDate nextMon = LocalDate.of(2026, 6, 22);

        System.out.println("=== Monday: sell ₫50M of VNM ===");
        engine.bookSale(alice, 50_000_000L, mon);
        show(alice, "after sale");

        System.out.println("\n=== Monday: try to withdraw ₫120M — should fail (only ₫100M settled) ===");
        try { engine.withdraw(alice, 120_000_000L); }
        catch (Exception e) { System.out.println("  rejected: " + e.getMessage()); }

        System.out.println("\n=== Monday: buy ₫130M of FPT — uses settled + pending (rolling reuse) ===");
        engine.bookBuy(alice, 130_000_000L, mon);
        show(alice, "after buy");

        System.out.println("\n=== Wednesday morning settlement run ===");
        engine.onSettlementDay(alice, wed);
        show(alice, "post-Wed");
        System.out.println("  → Monday's sale & buy both cleared; net = +50M − 130M = -80M");

        System.out.println("\n=== Tuesday: sell ₫200M of HPG; Thursday: try to withdraw ₫100M ===");
        engine.bookSale(alice, 200_000_000L, tue);
        show(alice, "after Tue sale");
        System.out.println("  attempt early withdraw of ₫100M on Wednesday (sale not yet settled):");
        try { engine.withdraw(alice, 100_000_000L); }
        catch (Exception e) { System.out.println("  rejected: " + e.getMessage()); }
        System.out.println("  Thursday settlement run:");
        engine.onSettlementDay(alice, thu);
        show(alice, "post-Thu");
        engine.withdraw(alice, 100_000_000L);
        System.out.println("  withdrew ₫100M:");
        show(alice, "post-WD");

        System.out.println("\n=== Failed settlement scenario ===");
        var bob = new Account("bob", 0);
        engine.bookSale(bob, 30_000_000L, wed);
        engine.failBucket(bob, engine.cycle.settlementOf(wed), "counterparty default");
        engine.onSettlementDay(bob, fri);
        show(bob, "bob");
        System.out.println("  → bucket FAILED, settled cash NOT credited");

        System.out.println("\n=== Audit log (Alice) ===");
        alice.audit.forEach(e -> System.out.printf("  [%s] %-12s %s%n", e.at(), e.kind(), e.detail()));
    }
}
