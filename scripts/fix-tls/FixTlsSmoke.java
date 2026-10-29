import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.Message;
import quickfix.MemoryStoreFactory;
import quickfix.ScreenLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
import quickfix.field.Account;
import quickfix.field.ClOrdID;
import quickfix.field.OrdType;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderStatusRequest;

import java.io.FileInputStream;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * ADR-095 smoke (FR-FTL-05): log on through the external TLS entry with quickfixj-client.cfg, cross a Sell and a Buy
 * on two of the session's accounts, and confirm at the effect end: the Buy's Filled ExecutionReport, and the Sell's
 * OrderStatusRequest answered Filled from the order read model (the database's orderbook, not the gateway).
 * Prints RESULT key=value lines for fix-tls-smoke.sh. Env: FIX_PASSWORD FIX_TRUSTSTORE_PASSWORD TICKER PX ACCT_A ACCT_B.
 */
public final class FixTlsSmoke implements Application {
    private final BlockingQueue<Message> rx = new LinkedBlockingQueue<>();
    private volatile SessionID session;

    public static void main(final String[] args) throws Exception {
        final Map<String, String> e = System.getenv();
        System.setProperty("fix.password", e.get("FIX_PASSWORD"));   // secrets come from env, never argv
        System.setProperty("fix.truststore.password", e.get("FIX_TRUSTSTORE_PASSWORD"));
        final SessionSettings settings = new SessionSettings(new FileInputStream(args[0]));
        final FixTlsSmoke app = new FixTlsSmoke();
        final SocketInitiator initiator = new SocketInitiator(app, new MemoryStoreFactory(), settings,
            new ScreenLogFactory(false, false, false), new DefaultMessageFactory());
        initiator.start();
        try {
            System.exit(app.run(e.get("TICKER"), Double.parseDouble(e.get("PX")), e.get("ACCT_A"), e.get("ACCT_B")));
        } finally {
            initiator.stop(true);
        }
    }

    private int run(final String ticker, final double px, final String acctA, final String acctB) throws Exception {
        final long t0 = System.nanoTime();
        while (session == null || !Session.lookupSession(session).isLoggedOn()) {
            if (System.nanoTime() - t0 > TimeUnit.SECONDS.toNanos(20)) {
                return fail("no Logon(A) over TLS within 20 s");
            }
            Thread.sleep(50);
        }
        ok("TLS logon as " + session.getSenderCompID());
        result("logon_ok", "1");
        final String tag = "tls" + (System.currentTimeMillis() % 1_000_000_000L);
        send(order(tag + "-s", acctB, Side.SELL, ticker, px));
        final Message sellNew = await(m -> is(m, "8") && cl(m).equals(tag + "-s") && ("0".equals(get(m, 39))), 20);
        if (sellNew == null) {
            return fail("the resting Sell was not acknowledged New");
        }
        ok("Sell " + tag + "-s rests: OrderID=" + get(sellNew, 37));
        send(order(tag + "-b", acctA, Side.BUY, ticker, px));
        final Message fill = await(m -> is(m, "8") && cl(m).equals(tag + "-b") && "2".equals(get(m, 39)), 20);
        if (fill == null) {
            return fail("no Filled ExecutionReport for the crossing Buy within 20 s");
        }
        ok("Buy " + tag + "-b filled: LastPx=" + get(fill, 31) + " CumQty=" + get(fill, 14));
        result("fill_px", get(fill, 31));
        result("sell_order_id", get(sellNew, 37));
        result("buy_order_id", get(fill, 37));
        // The read model trails the engine (status is off-consensus): ask until it shows the fill, up to 15 s.
        for (int i = 0; i < 15; i++) {
            final OrderStatusRequest h = new OrderStatusRequest(new ClOrdID(tag + "-s"), new Side(Side.SELL));
            h.set(new OrderID(get(sellNew, 37)));
            h.set(new Symbol(ticker));
            h.set(new Account(acctB));
            send(h);
            final Message st = await(m -> is(m, "8") && cl(m).equals(tag + "-s") && "I".equals(get(m, 150)), 5);
            if (st != null && "2".equals(get(st, 39))) {
                ok("OrderStatusRequest -> OrdStatus=2 (Filled) from the read model");
                result("status_filled", "1");
                System.out.println("FIX-TLS-SMOKE OK");
                return 0;
            }
            Thread.sleep(1000);
        }
        return fail("the read model never reported the Sell Filled (OrderStatusRequest)");
    }

    private static NewOrderSingle order(final String id, final String acct, final char side, final String sym,
                                        final double px) {
        final NewOrderSingle d = new NewOrderSingle(new ClOrdID(id), new Side(side),
            new TransactTime(LocalDateTime.now(java.time.ZoneOffset.UTC)), new OrdType(OrdType.LIMIT));
        d.set(new Account(acct));
        d.set(new Symbol(sym));
        d.set(new OrderQty(1));
        d.set(new Price(px));
        return d;
    }

    private void send(final Message m) throws Exception {
        Session.sendToTarget(m, session);
    }

    private Message await(final Predicate<Message> p, final int seconds) throws InterruptedException {
        final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        for (long left; (left = end - System.nanoTime()) > 0; ) {
            final Message m = rx.poll(left, TimeUnit.NANOSECONDS);
            if (m == null) {
                break;
            }
            if (p.test(m)) {
                return m;
            }
            if (is(m, "8") && "8".equals(get(m, 39))) {
                System.out.println("    rejected " + cl(m) + ": " + get(m, 58));
            }
        }
        return null;
    }

    private static boolean is(final Message m, final String type) {
        try {
            return type.equals(m.getHeader().getString(35));
        } catch (final Exception e) {
            return false;
        }
    }

    private static String cl(final Message m) {
        return get(m, 11);
    }

    private static String get(final Message m, final int tag) {
        try {
            return m.isSetField(tag) ? m.getString(tag) : "";
        } catch (final quickfix.FieldNotFound e) {
            return "";
        }
    }

    private static int fail(final String why) {
        System.out.println("[FAIL] " + why);
        return 1;
    }

    private static void ok(final String what) {
        System.out.println("[ok] " + what);
    }

    private static void result(final String k, final String v) {
        System.out.println("RESULT " + k + "=" + v);
    }

    @Override public void onCreate(final SessionID id) { session = id; }
    @Override public void onLogon(final SessionID id) { }
    @Override public void onLogout(final SessionID id) { }
    @Override public void toAdmin(final Message m, final SessionID id) { }
    @Override public void fromAdmin(final Message m, final SessionID id) {
        if (is(m, "5")) {
            System.out.println("    Logout: " + get(m, 58));
        }
    }
    @Override public void toApp(final Message m, final SessionID id) { }
    @Override public void fromApp(final Message m, final SessionID id) { rx.add(m); }
}
