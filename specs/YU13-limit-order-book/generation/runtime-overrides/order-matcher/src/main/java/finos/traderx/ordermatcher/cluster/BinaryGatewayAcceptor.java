package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.InputEvent;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Binary order-entry acceptor (brief 03, lever 4): a fixed-layout, length-prefixed binary fast path
 * for order lifecycle commands, offered ALONGSIDE the FIX-text acceptor exactly as real venues offer
 * OUCH/ETI beside FIX. It exists to delete the one thing the pipelining lever left as the binding
 * constraint — gateway GC from QuickFIX/J parsing FIX text into a per-message object tree. Here each
 * message is decoded through a flyweight over a per-connection reusable buffer: no parse tree, no
 * per-message allocation on the decode path.
 *
 * <p>It is a peer of {@link FixGatewayAcceptor}: it terminates the counterparty session on the gateway
 * and forwards through the SAME {@link OrderSubmitter} seam, so it inherits the whole pipelined
 * in-flight window, FIFO ack correlation, backpressure and failover transparency unchanged. The
 * cluster receives byte-identical {@code InputEventMessage}s whether an order arrived as FIX, REST or
 * binary — so this is a gateway/wire change with zero determinism impact (no member roll, no format
 * bump). Concurrency comes from many connections, each a thread blocking on its own committed ack —
 * the identical shape QuickFIX/J's {@code ThreadedSocketAcceptor} uses, which keeps the FIX-vs-binary
 * comparison a clean single-variable A/B.
 *
 * <p>Wire (all little-endian, incl. the 2-byte length prefix). Frame: {@code [u16 len][payload]};
 * {@code payload[0]} is the message type.
 * <pre>
 *   NEW     (type 1, 32B): 1:side(0=BUY,1=SELL) 4:u32 account 8:u32 security 12:i32 qty
 *                          16:i64 limitPx(x1e6) 24:u64 clOrdId
 *   CANCEL  (type 2, 16B): 4:u32 orderRef 8:u64 clOrdId
 *   REPLACE (type 3, 32B): 4:u32 orderRef 8:i32 qty 16:i64 limitPx(x1e6) 24:u64 clOrdId
 *   ACK     (type 0x81,16B): 1:status(1=accepted,0=rejected,2=ambiguous,3=protocol-error)
 *                          2:u8 kind(OutputEvent) 3:u8 riskReason 4:u32 orderRef 8:u64 clOrdId(echo)
 * </pre>
 * {@code clOrdId} is the client's own {@code uint64}: used directly as the engine idempotency key
 * (0 = none, not hashed) and echoed in the ack for correlation. {@code security} is pre-resolved
 * numeric, so the decode path never builds a String.
 */
public final class BinaryGatewayAcceptor {
    private static final Logger log = LoggerFactory.getLogger(BinaryGatewayAcceptor.class);
    private static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;

    static final int MSG_NEW = 1;
    static final int MSG_CANCEL = 2;
    static final int MSG_REPLACE = 3;
    static final int ACK_TYPE = 0x81;
    static final int ACK_PAYLOAD_LEN = 16;

    static final byte STATUS_REJECTED = 0;
    static final byte STATUS_ACCEPTED = 1;
    static final byte STATUS_AMBIGUOUS = 2;      // no committed decision — client reconciles, never a reject
    static final byte STATUS_PROTOCOL_ERROR = 3; // unknown message type / bad frame

    // Largest client frame is NEW/REPLACE at 32B payload; 64 leaves headroom without unbounding the
    // per-connection read buffer (a length prefix past this is a framing error, closed).
    static final int MAX_PAYLOAD = 64;
    private static final int SIDE_SELL_WIRE = InputEvent.SIDE_SELL; // 1

    private final OrderSubmitter submitter;
    private final int port;
    private final LongAdder decodedFrames = new LongAdder();
    private final LongAdder acknowledgedFrames = new LongAdder();
    private final GatewayLatencyDecomposition metrics; // null unless LATENCY_DECOMP=1 (LATENCY-01 Phase A)
    private volatile boolean running = true;
    private ServerSocket serverSocket;

    public BinaryGatewayAcceptor(final OrderSubmitter submitter, final int port) {
        this(submitter, port, null);
    }

    public BinaryGatewayAcceptor(final OrderSubmitter submitter, final int port,
                                 final GatewayLatencyDecomposition metrics) {
        this.submitter = submitter;
        this.port = port;
        this.metrics = metrics;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        final Thread acceptLoop = new Thread(this::acceptLoop, "binary-acceptor");
        acceptLoop.setDaemon(true);
        acceptLoop.start();
        log.info("Binary gateway acceptor on :{}", port);
    }

    /** Actual bound port (useful when started on 0 for an ephemeral port; also logged at startup). */
    int boundPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close(); // breaks accept()
            }
        } catch (final IOException ignore) {
            // shutting down
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true); // per-order request/response — Nagle would add ~40ms
                final Thread handler = new Thread(() -> handle(socket), "binary-conn");
                handler.setDaemon(true);
                handler.start();
            } catch (final IOException e) {
                if (running) {
                    log.warn("binary accept failed: {}", e.toString());
                }
                return; // socket closed on stop(), or fatal
            }
        }
    }

    /** One connection: read framed commands into a reusable buffer, decode+submit each, write its ack.
     *  Everything reused per-connection; the decode path allocates nothing (the pipelined submit path
     *  allocates the same PendingOrder/future/ExecResult the FIX path does — that floor is the point
     *  of comparison, not a regression). */
    private void handle(final Socket socket) {
        final byte[] reqBytes = new byte[MAX_PAYLOAD];
        final byte[] ackBytes = new byte[2 + ACK_PAYLOAD_LEN];
        final byte[] lenBuf = new byte[2];
        final UnsafeBuffer req = new UnsafeBuffer(reqBytes);
        final UnsafeBuffer ack = new UnsafeBuffer(ackBytes);
        try (Socket s = socket;
             DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(s.getInputStream()));
             OutputStream out = new java.io.BufferedOutputStream(s.getOutputStream())) {
            while (running) {
                in.readFully(lenBuf, 0, 2);
                final int len = (lenBuf[0] & 0xff) | ((lenBuf[1] & 0xff) << 8);
                if (len < 1 || len > MAX_PAYLOAD) {
                    log.warn("binary frame length {} out of range; closing", len);
                    return;
                }
                in.readFully(reqBytes, 0, len);
                decodedFrames.increment();
                // LATENCY-01 Phase A: one sample decision per frame, reused for this order's segments so
                // a sampled order is coherent. t_recv = bytes now in / decode start (gateway clock).
                final boolean sample = metrics != null && metrics.sample();
                final long tRecv = sample ? System.nanoTime() : 0L;
                final int ackLen = dispatch(req, 0, len, ack, tRecv);
                // t_egress = dispatch returned = committed ack in hand, before the reply write.
                final long tEgress = sample ? System.nanoTime() : 0L;
                out.write(ackBytes, 0, ackLen);
                out.flush();
                if (sample) {
                    final long tReply = System.nanoTime(); // reply encoded + socket write flushed
                    metrics.recordReply(tReply - tEgress);
                    metrics.recordTotal(tReply - tRecv);
                }
                acknowledgedFrames.increment();
            }
        } catch (final EOFException eof) {
            // clean disconnect
        } catch (final IOException e) {
            log.debug("binary connection closed: {}", e.toString());
        }
    }

    long decodedFrames() {
        return decodedFrames.sum();
    }

    long acknowledgedFrames() {
        return acknowledgedFrames.sum();
    }

    /**
     * Decode one client frame at {@code [offset, offset+len)} and submit it through the seam, writing
     * the response frame (length prefix + payload) into {@code ackOut}; returns the total bytes to
     * write. Package-private and socket-free so {@code BinaryGatewayAcceptorTest} drives it against a
     * fake submitter. Zero-alloc: fields read via flyweight getters, no String, no boxing.
     */
    int dispatch(final DirectBuffer in, final int offset, final int len, final MutableDirectBuffer ackOut) {
        return dispatch(in, offset, len, ackOut, 0L);
    }

    /** As {@link #dispatch(DirectBuffer, int, int, MutableDirectBuffer)}, but {@code tRecvNanos != 0}
     *  means this frame is sampled: record the decode segment (t_recv &rarr; just-before-submit) on the
     *  gateway clock. The submit call itself carries no timestamp — the owner-thread and reply segments
     *  are timed on their own side (see {@link GatewayLatencyDecomposition}). */
    int dispatch(final DirectBuffer in, final int offset, final int len, final MutableDirectBuffer ackOut,
                 final long tRecvNanos) {
        final int msgType = in.getByte(offset) & 0xff;
        final long clOrdId;
        final OrderSubmitter.ExecResult r;
        switch (msgType) {
            case MSG_NEW: {
                if (len < 32) {
                    return writeAck(ackOut, STATUS_PROTOCOL_ERROR, (byte) 0, (byte) 0, 0, 0L);
                }
                final char side = (in.getByte(offset + 1) == SIDE_SELL_WIRE) ? 'S' : 'B';
                final int account = in.getInt(offset + 4, LE);
                final int security = in.getInt(offset + 8, LE);
                final int qty = in.getInt(offset + 12, LE);
                final long limitPx = in.getLong(offset + 16, LE);
                clOrdId = in.getLong(offset + 24, LE);
                if (tRecvNanos != 0) {
                    metrics.recordDecode(System.nanoTime() - tRecvNanos);
                }
                r = submitter.submitOrder(clOrdId, account, security, side, qty, limitPx);
                break;
            }
            case MSG_CANCEL: {
                if (len < 16) {
                    return writeAck(ackOut, STATUS_PROTOCOL_ERROR, (byte) 0, (byte) 0, 0, 0L);
                }
                final int orderRef = in.getInt(offset + 4, LE);
                clOrdId = in.getLong(offset + 8, LE);
                if (tRecvNanos != 0) {
                    metrics.recordDecode(System.nanoTime() - tRecvNanos);
                }
                r = submitter.submitCancel(orderRef);
                break;
            }
            case MSG_REPLACE: {
                if (len < 32) {
                    return writeAck(ackOut, STATUS_PROTOCOL_ERROR, (byte) 0, (byte) 0, 0, 0L);
                }
                final int orderRef = in.getInt(offset + 4, LE);
                final int qty = in.getInt(offset + 8, LE);
                final long limitPx = in.getLong(offset + 16, LE);
                clOrdId = in.getLong(offset + 24, LE);
                if (tRecvNanos != 0) {
                    metrics.recordDecode(System.nanoTime() - tRecvNanos);
                }
                r = submitter.submitReplace(orderRef, clOrdId, qty, limitPx);
                break;
            }
            default:
                return writeAck(ackOut, STATUS_PROTOCOL_ERROR, (byte) 0, (byte) 0, 0, 0L);
        }
        if (r == null) {
            // Post-publish ambiguity: the command may yet commit, so the client must NOT read this as
            // a rejection (same rule the REST/FIX front ends follow when submit returns null).
            return writeAck(ackOut, STATUS_AMBIGUOUS, (byte) 0, (byte) 0, 0, clOrdId);
        }
        return writeAck(ackOut, r.accepted() ? STATUS_ACCEPTED : STATUS_REJECTED, r.kind(),
            r.riskReason(), r.orderRef(), clOrdId);
    }

    private static int writeAck(final MutableDirectBuffer ackOut, final byte status, final byte kind,
                                final byte riskReason, final int orderRef, final long clOrdId) {
        ackOut.putShort(0, (short) ACK_PAYLOAD_LEN, LE);
        final int p = 2;
        ackOut.putByte(p, (byte) ACK_TYPE);
        ackOut.putByte(p + 1, status);
        ackOut.putByte(p + 2, kind);
        ackOut.putByte(p + 3, riskReason);
        ackOut.putInt(p + 4, orderRef, LE);
        ackOut.putLong(p + 8, clOrdId, LE);
        return 2 + ACK_PAYLOAD_LEN;
    }
}
