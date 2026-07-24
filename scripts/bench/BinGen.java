// BinGen.java — compiled, thread-per-connection BINARY order-entry load generator.
//
// WHY THIS EXISTS: every per-order number all week ("the 12k ceiling") was harness-limited, not
// engine-limited — one Node event loop reading+writing acks caps well below cluster capacity. Node's
// event loop serialises all socket I/O on one thread; this generator gives every connection its own
// OS thread (writer + reader), so the generator is CPU/kernel-bound, never event-loop-bound. Same wire
// and same unique-key / leader-aware design as codeX's bin-multi.mjs, in a language that can actually
// over-offer. Run with `java BinGen.java` (JDK 21+ single-file launch — JIT-compiled, no build step).
//
// ISOLATION RULE: prove this generator's own ceiling against an echo (BinEcho.java) FIRST, so any
// cluster number you later report is known to be below the generator's offer rate. A ceiling measured
// with a generator that caps first is worthless — that is the whole lesson of the 12k number.
//
// Wire (little-endian incl. the 2-byte length prefix), matching BinaryGatewayAcceptor:
//   NEW  = [u16 len=32][u8 type=1][u8 side(0=BUY,1=SELL)][2 pad][u32 acct][u32 sec][i32 qty]
//          [i64 limitPx x1e6][u64 clOrdId]
//   ACK  = [u16 len=16][u8 0x81][u8 status][u8 kind][u8 riskReason][u32 orderRef][u64 clOrdId echo]
//
// Env knobs: GATEWAYS=host:port,host:port (connection i pinned to i%G — exact per-gateway placement),
//   SESSIONS, SECS, MODE=blast|paced, RATE (per-conn/s, paced), TOTAL (=> RATE=TOTAL/SESSIONS),
//   BATCH (frames per write syscall — the throughput lever), RUN_ID (1..65535), POD_INDEX (0..255),
//   SECURITY (pre-resolved numeric id), ACCT_BUY, ACCT_SELL, QTY, PRICE, WARMUP_MS.

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

public final class BinGen {
    // ---- config ----
    static String[] GATEWAYS = env("GATEWAYS", "localhost:18140").split(",");
    static final int SESSIONS = envi("SESSIONS", 32);
    static final int SECS = envi("SECS", 20);
    static final String MODE = env("MODE", "blast"); // blast = find the ceiling; paced = fixed RATE
    static final long TOTAL = envl("TOTAL", 0);
    static final double RATE = TOTAL > 0 ? (double) TOTAL / SESSIONS : envd("RATE", 50);
    static final int BATCH = envi("BATCH", 256);     // frames coalesced into one write()
    static final long RUN_ID = envl("RUN_ID", 1 + (System.nanoTime() & 0x7fff));
    static final long POD_INDEX = envl("POD_INDEX", 0);
    static final int SECURITY = envi("SECURITY", 1);
    static final int ACCT_BUY = envi("ACCT_BUY", 42422);
    static final int ACCT_SELL = envi("ACCT_SELL", 22214);
    static final int QTY = envi("QTY", 10);
    static final long PX_TICKS = Math.round(envd("PRICE", 100.0) * 1_000_000);
    static final int WARMUP_MS = envi("WARMUP_MS", 1000);
    // Wall-clock epoch (ms) all pods open their load window at, so a runner sampling the leader's
    // nextOrderRef delta covers exactly the same window across every generator instance. 0 = start now.
    static final long START_AT_MS = envl("START_AT_MS", 0);

    // Per-connection latency arrays. Defaults are sized for the CLUSTER path (thread-per-conn acceptor
    // is synchronous => ~1 order in-flight/conn), so many connections fit a modest heap:
    //   RING (send-time ring) 1<<14 + LAT (RTT samples) 1<<16 = ~640 KiB/conn, so 250 conns ~= 160 MiB.
    // The old flat 2x(1<<20)=16 MiB/conn OOM'd a 512 MiB heap at ~30 conns — which capped the ladder's
    // in-flight window (the very hop we found binding). Bump RING_BITS for the isolation blast (few
    // conns, huge in-flight); a wrapped ring only undercounts latency (warned), never the offer rate.
    static final int RING = 1 << envi("RING_BITS", 14);
    static final int LAT_CAP = 1 << envi("LAT_CAP_BITS", 16);

    static volatile long windowStartNanos;
    static volatile long windowEndNanos;

    public static void main(String[] args) throws Exception {
        if (RUN_ID < 1 || RUN_ID > 65535 || POD_INDEX < 0 || POD_INDEX > 255 || SESSIONS > 65536) {
            System.out.println("[FAIL] RUN_ID(1..65535)/POD_INDEX(0..255)/SESSIONS(<=65536) out of range");
            System.exit(1);
        }
        String[] hosts = new String[GATEWAYS.length];
        int[] ports = new int[GATEWAYS.length];
        for (int g = 0; g < GATEWAYS.length; g++) {
            String[] hp = GATEWAYS[g].trim().split(":");
            hosts[g] = hp[0];
            ports[g] = Integer.parseInt(hp[1]);
        }
        long offeredTarget = MODE.equals("paced") ? Math.round(RATE * SESSIONS) : -1;
        System.out.printf("BinGen: %d connections across %d gateways (%s), run %d pod %d, mode=%s%s, "
                + "batch=%d, %ds, sec=#%d qty %d, buy=%d sell=%d%n",
            SESSIONS, GATEWAYS.length, String.join(",", GATEWAYS), RUN_ID, POD_INDEX, MODE,
            offeredTarget > 0 ? " target=" + offeredTarget + "/s" : "", BATCH, SECS, SECURITY,
            QTY, ACCT_BUY, ACCT_SELL);

        List<Conn> conns = new ArrayList<>();
        for (int i = 0; i < SESSIONS; i++) {
            int g = i % GATEWAYS.length;
            Conn c = new Conn(i, hosts[g], ports[g], g);
            if (c.connect()) conns.add(c);
        }
        int[] perGw = new int[GATEWAYS.length];
        for (Conn c : conns) perGw[c.gw]++;
        System.out.printf("connected: %d/%d; per-gateway: %s%n", conns.size(), SESSIONS,
            Arrays.toString(perGw));
        if (conns.isEmpty()) { System.out.println("[FAIL] no connections"); System.exit(1); }

        Thread.sleep(WARMUP_MS);

        // Barrier: every pod opens its window at the same wall-clock epoch so an external sampler can
        // read the leader nextOrderRef delta over exactly this window across all generator instances.
        if (START_AT_MS > 0) {
            long wait = START_AT_MS - System.currentTimeMillis();
            if (wait < -1000) { System.out.println("[FAIL] synchronized start missed by " + (-wait) + "ms"); System.exit(1); }
            if (wait > 0) { System.out.println("barrier: waiting " + wait + "ms for epoch " + START_AT_MS); Thread.sleep(wait); }
        }

        // Synchronised load window shared by every thread; server/echo counters are read over exactly it.
        long now = System.nanoTime();
        windowStartNanos = now;
        windowEndNanos = now + SECS * 1_000_000_000L;

        List<Thread> threads = new ArrayList<>();
        for (Conn c : conns) {
            Thread w = new Thread(c::writeLoop, "gen-w-" + c.i);
            Thread r = new Thread(c::readLoop, "gen-r-" + c.i);
            c.reader = r;
            threads.add(w); threads.add(r);
            w.start(); r.start();
        }

        // Live progress each 2s so a stall shows as a flat line, not a mystery at the end.
        Thread progress = new Thread(() -> {
            try {
                while (System.nanoTime() < windowEndNanos) {
                    Thread.sleep(2000);
                    long off = 0, cmp = 0;
                    for (Conn c : conns) { off += c.offered.sum(); cmp += c.completed.sum(); }
                    double t = (System.nanoTime() - windowStartNanos) / 1e9;
                    System.out.printf("  t=%.0fs offered=%d completed=%d inflight=%d offer=%.0f/s ack=%.0f/s%n",
                        t, off, cmp, off - cmp, off / t, cmp / t);
                }
            } catch (InterruptedException ignore) { }
        }, "gen-progress");
        progress.setDaemon(true);
        progress.start();

        // Snapshot the counters at the load-window boundary — rates are over the window ONLY, never the
        // post-window ack drain (that dilution was the bug that under-reported the offer rate).
        long sleepMs = (windowEndNanos - System.nanoTime()) / 1_000_000L;
        if (sleepMs > 0) Thread.sleep(sleepMs);
        double win = (System.nanoTime() - windowStartNanos) / 1e9;
        long offWin = 0, cmpWin = 0;
        for (Conn c : conns) { offWin += c.offered.sum(); cmpWin += c.completed.sum(); }

        for (Thread t : threads) t.join(); // writers hold ~3s for the reader to drain in-flight acks

        long cmp = 0, stalls = 0, mism = 0, maxInflight = 0;
        long[] lat = collectLatencies(conns);
        for (Conn c : conns) {
            cmp += c.completed.sum();
            stalls += c.writeStalls.sum(); mism += c.mismatches.sum();
            maxInflight = Math.max(maxInflight, c.maxInflight);
        }
        System.out.println("\n=== RESULT (binary per-order generator) ===");
        System.out.printf("connections up:      %d/%d  (per-gateway %s)%n", conns.size(), SESSIONS,
            Arrays.toString(perGw));
        System.out.printf("offered (in window): %d  (%.0f/s over %.1fs)  <-- generator offer ceiling%n",
            offWin, offWin / win, win);
        System.out.printf("acks (in window):    %d  (%.0f/s)%n", cmpWin, cmpWin / win);
        System.out.printf("completed total:     %d  (after drain; == offered => reader lossless)%n", cmp);
        System.out.printf("max in-flight/conn:  %d%n", maxInflight);
        System.out.printf("write stalls (>1ms): %d   (blast: nonzero just means loopback/echo is the far "
            + "limit, still >> any cluster hop)%n", stalls);
        System.out.printf("ack seq mismatches:  %d   (MUST be 0 — non-FIFO ack would corrupt correlation)%n", mism);
        if (lat.length > 0) {
            Arrays.sort(lat);
            String warn = maxInflight > RING ? "  [WARN in-flight>ring: latency undercounts]" : "";
            System.out.printf("latency ack RTT p50 %dus  p99 %dus  max %dus%s%n",
                lat[(int) (lat.length * 0.50)] / 1000, lat[(int) (lat.length * 0.99)] / 1000,
                lat[lat.length - 1] / 1000, warn);
        }
        System.exit(mism == 0 ? 0 : 2);
    }

    static long[] collectLatencies(List<Conn> conns) {
        int n = 0;
        for (Conn c : conns) n += c.latCount;
        long[] all = new long[n];
        int o = 0;
        for (Conn c : conns) {
            int take = Math.min(c.latCount, c.lat.length);
            System.arraycopy(c.lat, 0, all, o, take);
            o += take;
        }
        return Arrays.copyOf(all, o);
    }

    /** One connection: a writer thread blasts (or paces) fresh-key NEW frames coalesced into BATCH-sized
     *  writes; a reader thread drains acks FIFO, correlating by clOrdId and timing the RTT. */
    static final class Conn {
        final int i, gw;
        final String host; final int port;
        final long base;            // 16b run | 8b pod | 16b conn | 24b seq
        final int side, account;
        Socket sock; OutputStream out; DataInputStream in;
        Thread reader;

        final LongAdder offered = new LongAdder();
        final LongAdder completed = new LongAdder();
        final LongAdder writeStalls = new LongAdder();
        final LongAdder mismatches = new LongAdder();
        volatile long maxInflight = 0;

        final long[] sendNanos = new long[RING];  // seq%RING -> intended send time
        final long[] lat = new long[LAT_CAP];      // per-conn RTT samples (ns)
        int latCount = 0;

        Conn(int i, String host, int port, int gw) {
            this.i = i; this.host = host; this.port = port; this.gw = gw;
            boolean buy = (i % 2) == 0;
            this.side = buy ? 0 : 1;
            this.account = buy ? ACCT_BUY : ACCT_SELL;
            this.base = (RUN_ID << 48) | (POD_INDEX << 40) | ((long) i << 24);
        }

        boolean connect() {
            try {
                sock = new Socket();
                sock.setTcpNoDelay(true);
                sock.connect(new InetSocketAddress(host, port), 8000);
                out = new BufferedOutputStream(sock.getOutputStream(), 1 << 16);
                in = new DataInputStream(new BufferedInputStream(sock.getInputStream(), 1 << 16));
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        void writeLoop() {
            byte[] buf = new byte[BATCH * 34];
            long seq = 0;
            double period = RATE > 0 ? 1e9 / RATE : 0; // ns between orders, paced mode
            boolean paced = MODE.equals("paced");
            try {
                while (System.nanoTime() < windowEndNanos) {
                    int n = 0;
                    long now = System.nanoTime();
                    while (n < BATCH) {
                        if (paced) {
                            long due = windowStartNanos + (long) (seq * period);
                            if (due > now) break; // caught up to schedule; flush what we have
                        }
                        long clOrdId = base + seq;
                        encodeNew(buf, n * 34, side, account, SECURITY, QTY, PX_TICKS, clOrdId);
                        // Coordinated-omission-safe: record the INTENDED send time in paced mode (the
                        // schedule slot), not the actual `now`. If the writer slips behind schedule the
                        // slip is charged to latency instead of being silently omitted — exactly the tail
                        // the decomposition must not hide. Blast has no schedule, so `now` is intended.
                        sendNanos[(int) (seq & (RING - 1))] =
                            paced ? windowStartNanos + (long) (seq * period) : now;
                        seq++; n++;
                    }
                    if (n > 0) {
                        long w0 = System.nanoTime();
                        out.write(buf, 0, n * 34);
                        out.flush();
                        if (System.nanoTime() - w0 > 1_000_000L) writeStalls.increment();
                        offered.add(n);
                    } else if (paced) {
                        long due = windowStartNanos + (long) (seq * period);
                        long sleepNs = due - System.nanoTime();
                        if (sleepNs > 200_000) {
                            try { Thread.sleep(sleepNs / 1_000_000, (int) (sleepNs % 1_000_000)); }
                            catch (InterruptedException ignore) { }
                        }
                    }
                }
            } catch (IOException e) {
                // socket closed / peer gone; the window is ending anyway
            }
            // Let the reader drain in-flight acks, then close.
            try { Thread.sleep(3000); } catch (InterruptedException ignore) { }
            try { sock.close(); } catch (IOException ignore) { }
        }

        void readLoop() {
            byte[] payload = new byte[64];
            long expectSeq = 0;
            try {
                while (true) {
                    int len = readLenLE();
                    if (len < 1 || len > 64) return;
                    in.readFully(payload, 0, len);
                    long recv = System.nanoTime();
                    long clOrdId = leLong(payload, 8);   // ACK echoes clOrdId at payload offset 8
                    long seq = clOrdId - base;
                    if (seq != expectSeq) mismatches.increment(); // TCP+per-frame reply => must be FIFO
                    expectSeq = seq + 1;
                    long sent = sendNanos[(int) (seq & (RING - 1))];
                    if (sent != 0 && latCount < lat.length) lat[latCount++] = recv - sent;
                    completed.increment();
                    // Diagnostic only — sample in-flight off the hot path (LongAdder.sum scans all cells).
                    if ((seq & 1023) == 0) {
                        long inflight = offered.sum() - completed.sum();
                        if (inflight > maxInflight) maxInflight = inflight;
                    }
                }
            } catch (IOException e) {
                // EOF at end of window
            }
        }

        int readLenLE() throws IOException {
            int lo = in.read();
            int hi = in.read();
            if (lo < 0 || hi < 0) throw new IOException("eof");
            return lo | (hi << 8);
        }
    }

    // ---- wire encode (little-endian, matches BinaryGatewayAcceptor NEW) ----
    static void encodeNew(byte[] b, int o, int side, int acct, int sec, int qty, long pxTicks, long clOrdId) {
        b[o] = 32; b[o + 1] = 0;                 // u16 len = 32
        b[o + 2] = 1;                            // type = NEW
        b[o + 3] = (byte) side;                  // side
        b[o + 4] = 0; b[o + 5] = 0;              // pad
        le32(b, o + 6, acct);
        le32(b, o + 10, sec);
        le32(b, o + 14, qty);
        le64(b, o + 18, pxTicks);
        le64(b, o + 26, clOrdId);
    }
    static void le32(byte[] b, int o, int v) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >>> 8); b[o + 2] = (byte) (v >>> 16); b[o + 3] = (byte) (v >>> 24);
    }
    static void le64(byte[] b, int o, long v) {
        for (int k = 0; k < 8; k++) b[o + k] = (byte) (v >>> (8 * k));
    }
    static long leLong(byte[] b, int o) {
        long v = 0;
        for (int k = 0; k < 8; k++) v |= (b[o + k] & 0xffL) << (8 * k);
        return v;
    }

    // ---- env helpers ----
    static String env(String k, String d) { String v = System.getenv(k); return v == null ? d : v; }
    static int envi(String k, int d) { String v = System.getenv(k); return v == null ? d : Integer.parseInt(v.trim()); }
    static long envl(String k, long d) { String v = System.getenv(k); return v == null ? d : Long.parseLong(v.trim()); }
    static double envd(String k, double d) { String v = System.getenv(k); return v == null ? d : Double.parseDouble(v.trim()); }
}
