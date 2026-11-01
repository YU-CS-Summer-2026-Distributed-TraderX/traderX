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
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TimeInForce;
import quickfix.field.TransactTime;
import quickfix.fix44.NewOrderSingle;

import java.io.FileInputStream;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * FR-FTL-08: one order from your machine through the external FIX-over-TLS entry, its ExecutionReports printed until
 * it is done (filled, cancelled, rejected) or rests (New, then 5 s of quiet). With PX a Limit (DAY), without a Market
 * (IOC). Env: FIX_PASSWORD FIX_TRUSTSTORE_PASSWORD ACCOUNT SIDE QTY TICKER [PX]. Exit 0 unless rejected or no answer.
 */
public final class FixOrder implements Application {
    private final BlockingQueue<Message> rx = new LinkedBlockingQueue<>();
    private volatile SessionID session;

    public static void main(final String[] args) throws Exception {
        final Map<String, String> e = System.getenv();
        System.setProperty("fix.password", e.get("FIX_PASSWORD"));   // secrets come from env, never argv
        System.setProperty("fix.truststore.password", e.get("FIX_TRUSTSTORE_PASSWORD"));
        final FixOrder app = new FixOrder();
        final SocketInitiator initiator = new SocketInitiator(app, new MemoryStoreFactory(),
            new SessionSettings(new FileInputStream(args[0])), new ScreenLogFactory(false, false, false), new DefaultMessageFactory());
        initiator.start();
        try {
            System.exit(app.run(e.get("ACCOUNT"), e.get("SIDE"), Long.parseLong(e.get("QTY")), e.get("TICKER"), e.get("PX")));
        } finally {
            initiator.stop(true);
        }
    }

    private int run(final String account, final String side, final long qty, final String ticker, final String px) throws Exception {
        final long t0 = System.nanoTime();
        while (session == null || !Session.lookupSession(session).isLoggedOn()) {
            if (System.nanoTime() - t0 > TimeUnit.SECONDS.toNanos(20)) {
                System.out.println("[FAIL] no Logon over TLS within 20 s");
                return 1;
            }
            Thread.sleep(50);
        }
        System.out.println("[ok] logged on over TLS as " + session.getSenderCompID());
        final String id = "cli-" + System.currentTimeMillis();
        final boolean limit = px != null && !px.isBlank();
        final NewOrderSingle o = new NewOrderSingle(new ClOrdID(id), new Side("Sell".equalsIgnoreCase(side) ? Side.SELL : Side.BUY),
            new TransactTime(LocalDateTime.now(java.time.ZoneOffset.UTC)), new OrdType(limit ? OrdType.LIMIT : OrdType.MARKET));
        o.set(new Account(account));
        o.set(new Symbol(ticker));
        o.set(new OrderQty(qty));
        if (limit) {
            o.set(new Price(Double.parseDouble(px)));
            o.set(new TimeInForce(TimeInForce.DAY));
        } else {
            o.set(new TimeInForce(TimeInForce.IMMEDIATE_OR_CANCEL));
        }
        Session.sendToTarget(o, session);
        System.out.printf("[sent] %s %s %d %s %s account %s%n", id, side, qty, ticker, limit ? "Limit DAY @ " + px : "Market IOC", account);
        boolean answered = false;
        for (Message m; (m = rx.poll(answered ? 5 : 20, TimeUnit.SECONDS)) != null; ) {
            if (!id.equals(get(m, 11))) {
                continue;
            }
            answered = true;
            final String st = get(m, 39);
            System.out.printf("[report] OrderID=%s status=%s filled=%s lastPx=%s %s%n", get(m, 37), STATUS.getOrDefault(st, st),
                get(m, 14), get(m, 31), get(m, 58));
            if ("8".equals(st)) {
                return 1;
            }
            if ("2".equals(st) || "4".equals(st)) {
                return 0;
            }
        }
        if (!answered) {
            System.out.println("[FAIL] no ExecutionReport within 20 s");
            return 1;
        }
        return 0;
    }

    private static final Map<String, String> STATUS = Map.of("0", "New", "1", "PartiallyFilled", "2", "Filled",
        "4", "Canceled", "8", "Rejected", "A", "PendingNew");

    private static String get(final Message m, final int tag) {
        try {
            return m.isSetField(tag) ? m.getString(tag) : "";
        } catch (final quickfix.FieldNotFound e) {
            return "";
        }
    }

    @Override public void onCreate(final SessionID id) { session = id; }
    @Override public void onLogon(final SessionID id) { }
    @Override public void onLogout(final SessionID id) { }
    @Override public void toAdmin(final Message m, final SessionID id) { }
    @Override public void fromAdmin(final Message m, final SessionID id) {
        try {
            if ("5".equals(m.getHeader().getString(35))) {
                System.out.println("    Logout: " + get(m, 58));
            }
        } catch (final Exception ignored) { }
    }
    @Override public void toApp(final Message m, final SessionID id) { }
    @Override public void fromApp(final Message m, final SessionID id) { rx.add(m); }
}
