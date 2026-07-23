// BinEcho.java — throwaway isolation target for BinGen.java. Speaks the ACK side of the
// BinaryGatewayAcceptor wire and nothing else: read each NEW frame, reply with a STATUS_ACCEPTED ack
// echoing clOrdId. NOT the engine — no matching, no risk, no idempotency. Its only job is to drain
// faster than any real cluster hop so the number BinGen reports against it is the GENERATOR's own
// ceiling, not the echo's. Thread-per-connection blocking I/O with batched ack flush (flush only when
// the read side is momentarily drained) keeps it well above the generator. Run: `java BinEcho.java`.
//
// If BinEcho ever becomes the bottleneck you would see BinGen's ack/s plateau below its offer/s while
// echo CPU pegs — raise this box's cores or shrink SESSIONS; do not report that plateau as a ceiling.

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.LongAdder;

public final class BinEcho {
    static final int PORT = envi("PORT", 18140);
    static final LongAdder frames = new LongAdder();

    public static void main(String[] args) throws IOException {
        ServerSocket ss = new ServerSocket(PORT, 1024);
        System.out.println("BinEcho on :" + ss.getLocalPort());
        // Heartbeat so a run log shows the echo kept draining.
        Thread hb = new Thread(() -> {
            long prev = 0;
            try {
                while (true) {
                    Thread.sleep(2000);
                    long now = frames.sum();
                    System.out.printf("  echo drained %d/s (total %d)%n", (now - prev) / 2, now);
                    prev = now;
                }
            } catch (InterruptedException ignore) { }
        }, "echo-hb");
        hb.setDaemon(true);
        hb.start();

        while (true) {
            Socket s = ss.accept();
            s.setTcpNoDelay(true);
            Thread t = new Thread(() -> handle(s), "echo-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    static void handle(Socket socket) {
        byte[] payload = new byte[64];
        byte[] ack = new byte[18];
        // Fixed ack header: len=16, type=0x81, status=1(accepted), kind=0, riskReason=0, orderRef=0.
        ack[0] = 16; ack[1] = 0; ack[2] = (byte) 0x81; ack[3] = 1;
        try (Socket s = socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16));
             OutputStream out = new BufferedOutputStream(s.getOutputStream(), 1 << 16)) {
            while (true) {
                int lo = in.read(), hi = in.read();
                if (lo < 0 || hi < 0) break;
                int len = lo | (hi << 8);
                if (len < 1 || len > 64) break;
                in.readFully(payload, 0, len);
                // Echo clOrdId (NEW payload offset 24) into ack payload offset 8 (ack bytes 10..17).
                System.arraycopy(payload, 24, ack, 10, 8);
                out.write(ack, 0, 18);
                frames.increment();
                if (in.available() == 0) out.flush(); // batch acks during a burst, flush when caught up
            }
        } catch (IOException e) {
            // client closed at end of window
        }
    }

    static int envi(String k, int d) { String v = System.getenv(k); return v == null ? d : Integer.parseInt(v.trim()); }
}
