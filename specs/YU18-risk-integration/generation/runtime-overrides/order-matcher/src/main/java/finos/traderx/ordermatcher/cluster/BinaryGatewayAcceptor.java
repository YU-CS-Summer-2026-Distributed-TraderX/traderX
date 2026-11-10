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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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
 * bump).
 *
 * <p>A connection is pipelined (FR-BSP-01, RI35). Its thread reads, decodes and submits frames without
 * waiting ({@link OrderSubmitter#submitOrderAsync} and its siblings queue each order for the cluster
 * before returning, so a connection's orders reach the log in the order they arrived), and its writer
 * thread writes each ack as that order's committed decision arrives. Acks on one connection may
 * therefore leave in a different order from their requests; the echoed {@code clOrdId} is the
 * client's correlation. At most {@link #MAX_IN_FLIGHT} frames per connection are read and not yet
 * acked; past that the connection thread stops reading, so TCP pushes back on the client. Before
 * this a session carried one order at a time, and a gateway's rate was sessions ÷ cluster round trip.
 *
 * <p>Wire (all little-endian, incl. the 2-byte length prefix). Frame: {@code [u16 len][payload]};
 * {@code payload[0]} is the message type.
 * <pre>
 *   NEW     (type 1, 32B): 1:side(0=BUY,1=SELL) 2:u8 flags 4:u32 account 8:u32 security 12:i32 qty
 *                          16:i64 limitPx(x1e6) 24:u64 clOrdId
 *   CANCEL  (type 2, 16B): 4:u32 orderRef 8:u64 clOrdId
 *   REPLACE (type 3, 32B): 4:u32 orderRef 8:i32 qty 16:i64 limitPx(x1e6) 24:u64 clOrdId
 *   ACK     (type 0x81,16B): 1:status(1=accepted,0=rejected,2=ambiguous,3=protocol-error)
 *                          2:u8 kind(OutputEvent) 3:u8 riskReason 4:u32 orderRef 8:u64 clOrdId(echo)
 * </pre>
 * {@code clOrdId} is the client's own {@code uint64}: used directly as the engine idempotency key
 * (0 = none, not hashed) and echoed in the ack for correlation. YU18 (ADR-090, FR-XOD-11): NEW flags bit 0
 * = sequenced, and then {@code clOrdId} reads {@code streamId:u32 (high) | seq:u32 (low)}, seq from 1; the
 * engine books each {@code (account, streamId, seq)} once, answers a resend with the original order, and
 * refuses a gap REJECTED/OUT_OF_SEQUENCE with the expected seq in the ack's orderRef. {@code security} is pre-resolved
 * numeric, so the decode path never builds a String.
 */
public final class BinaryGatewayAcceptor {
    private static final Logger log = LoggerFactory.getLogger(BinaryGatewayAcceptor.class);
    private static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;

    static final int MSG_NEW = 1;
    static final int MSG_CANCEL = 2;
    static final int MSG_REPLACE = 3;
    static final int NEW_FLAG_SEQUENCED = 1;
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
    /** FR-BSP-01: frames one connection may have read and not yet acked. */
    static final int MAX_IN_FLIGHT = 4096;
    // A submit waits at most ACK_TIMEOUT for a window slot plus ACK_TIMEOUT + 2 s for its answer, so a closed
    // connection's frames are all answered well inside this.
    private static final long DRAIN_TIMEOUT_MS = 60_000;
    /** A frame refused before any submit; identity-compared, never sent to the seam. */
    private static final OrderSubmitter.ExecResult PROTOCOL_ERROR = new OrderSubmitter.ExecResult(false, 0, (byte) 0);
    private static final Runnable STOP = () -> { };

    private final OrderSubmitter submitter;
    private final int port;
    private final LongAdder decodedFrames = new LongAdder();
    private final LongAdder acknowledgedFrames = new LongAdder();
    private final GatewayLatencyDecomposition metrics; // null unless LATENCY_DECOMP=1 (LATENCY-01 Phase A)
    /** ADR-096 §6: members older than ADR-090 cannot read a sequenced message, so BINARY_SEQUENCED=refuse answers a
     *  flagged NEW with a protocol error before anything is submitted. Unflagged NEWs are unchanged. */
    boolean refuseSequenced = "refuse".equals(System.getenv("BINARY_SEQUENCED"));
    int maxInFlight = MAX_IN_FLIGHT; // test seam
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

    /** One connection: read each frame into a reusable buffer, decode and submit it without waiting, and leave its
     *  ack to the connection's writer. The decode path allocates nothing; the submit path allocates the same
     *  PendingOrder/future/ExecResult the FIX path does, plus the completion's two queue entries. On EOF or a read
     *  error the frames already read are still answered (a client that half-closes gets every ack it is owed)
     *  before the socket closes. */
    private void handle(final Socket socket) {
        final byte[] reqBytes = new byte[MAX_PAYLOAD];
        final byte[] lenBuf = new byte[2];
        final UnsafeBuffer req = new UnsafeBuffer(reqBytes);
        final long[] clOrdIdOut = new long[1];
        try (Socket s = socket;
             DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(s.getInputStream()));
             OutputStream out = new java.io.BufferedOutputStream(s.getOutputStream())) {
            final Connection conn = new Connection(out, maxInFlight);
            final Thread writer = new Thread(conn::writeLoop, "binary-ack");
            writer.setDaemon(true);
            writer.start();
            try {
                while (running) {
                    conn.inFlight.acquire(); // full: stop reading, so TCP pushes back on the client
                    boolean submitted = false;
                    try {
                        in.readFully(lenBuf, 0, 2);
                        final int len = (lenBuf[0] & 0xff) | ((lenBuf[1] & 0xff) << 8);
                        if (len < 1 || len > MAX_PAYLOAD) {
                            log.warn("binary frame length {} out of range; closing", len);
                            break;
                        }
                        in.readFully(reqBytes, 0, len);
                        decodedFrames.increment();
                        // LATENCY-01 Phase A: one sample decision per frame, reused for this order's segments so
                        // a sampled order is coherent. t_recv = bytes now in / decode start (gateway clock).
                        final boolean sample = metrics != null && metrics.sample();
                        final long tRecv = sample ? System.nanoTime() : 0L;
                        final CompletableFuture<OrderSubmitter.ExecResult> answer =
                            submit(req, 0, len, tRecv, clOrdIdOut, conn);
                        final long clOrdId = clOrdIdOut[0];
                        answer.whenCompleteAsync((r, failure) -> conn.ack(failure == null ? r : null, clOrdId, tRecv),
                            conn);
                        submitted = true;
                    } finally {
                        if (!submitted) {
                            conn.inFlight.release(); // this slot never carried a frame
                        }
                    }
                }
            } catch (final EOFException eof) {
                // clean disconnect (or half-close): answer what was read, below
            } catch (final IOException e) {
                log.debug("binary connection closed: {}", e.toString());
            }
            if (!conn.inFlight.tryAcquire(conn.bound, DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                log.warn("binary connection closed with {} frames unanswered after {} ms",
                    conn.bound - conn.inFlight.availablePermits(), DRAIN_TIMEOUT_MS);
            }
            conn.execute(STOP);
            writer.join(DRAIN_TIMEOUT_MS);
        } catch (final IOException e) {
            log.debug("binary connection close: {}", e.toString());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * One connection's writer, and the executor its answers complete on: the gateway's completion work (reject
     * log, root span, wedge accounting) and then the ack write run here, off both the cluster client's owner thread
     * and the reading thread. Acks are written as they come and flushed once the queue is empty, so a burst of
     * answers leaves in one write; a sampled ack is flushed on its own so {@code reply} and {@code total} end at
     * the flush. After a write fails the writer keeps running the queue (every answer still releases its slot) and
     * writes nothing more.
     */
    private final class Connection implements Executor {
        final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
        final Semaphore inFlight;
        final int bound;
        final OutputStream out;
        final byte[] ackBytes = new byte[2 + ACK_PAYLOAD_LEN];
        final UnsafeBuffer ackBuf = new UnsafeBuffer(ackBytes);
        boolean broken; // writer thread only

        Connection(final OutputStream out, final int bound) {
            this.out = out;
            this.bound = bound;
            this.inFlight = new Semaphore(bound);
        }

        @Override
        public void execute(final Runnable task) {
            queue.add(task);
        }

        void writeLoop() {
            try {
                for (Runnable task; (task = queue.take()) != STOP; ) {
                    task.run();
                    if (queue.isEmpty() && !broken) {
                        out.flush();
                    }
                }
            } catch (final IOException e) {
                broken = true;
                drainAfterFailure();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void drainAfterFailure() {
            try {
                for (Runnable task; (task = queue.take()) != STOP; ) {
                    task.run();
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** Writer thread: one frame's ack ({@code r} null = no committed decision: ambiguous), then its slot back. */
        void ack(final OrderSubmitter.ExecResult r, final long clOrdId, final long tRecv) {
            try {
                if (broken) {
                    return; // client gone: the order's outcome stands in the engine, only the ack is lost
                }
                final long tEgress = tRecv != 0 ? System.nanoTime() : 0L; // the answer reached this connection
                out.write(ackBytes, 0, ackFor(ackBuf, r, clOrdId));
                if (tRecv != 0) {
                    out.flush();
                    final long tReply = System.nanoTime();
                    metrics.recordReply(tReply - tEgress);
                    metrics.recordTotal(tReply - tRecv);
                }
                acknowledgedFrames.increment();
            } catch (final IOException e) {
                broken = true;
            } finally {
                inFlight.release();
            }
        }
    }

    long decodedFrames() {
        return decodedFrames.sum();
    }

    long acknowledgedFrames() {
        return acknowledgedFrames.sum();
    }

    /**
     * Decode one client frame at {@code [offset, offset+len)}, submit it through the seam and wait, writing
     * the response frame (length prefix + payload) into {@code ackOut}; returns the total bytes to
     * write. Package-private and socket-free so {@code BinaryGatewayAcceptorTest} drives the decode against a
     * fake submitter.
     */
    int dispatch(final DirectBuffer in, final int offset, final int len, final MutableDirectBuffer ackOut) {
        final long[] clOrdId = new long[1];
        return ackFor(ackOut, submit(in, offset, len, 0L, clOrdId, Runnable::run).join(), clOrdId[0]);
    }

    /** Decode one frame and submit it without waiting; {@code clOrdIdOut[0]} gets the clOrdId its ack echoes.
     *  {@code tRecvNanos != 0} means this frame is sampled: record the decode segment (t_recv &rarr;
     *  just-before-submit) on the gateway clock; the owner-thread and reply segments are timed on their own side
     *  (see {@link GatewayLatencyDecomposition}). Zero-alloc decode: flyweight getters, no String, no boxing. */
    CompletableFuture<OrderSubmitter.ExecResult> submit(final DirectBuffer in, final int offset, final int len,
                                                        final long tRecvNanos, final long[] clOrdIdOut,
                                                        final Executor completions) {
        final int msgType = in.getByte(offset) & 0xff;
        final long clOrdId;
        clOrdIdOut[0] = 0L;
        switch (msgType) {
            case MSG_NEW: {
                if (len < 32) {
                    return CompletableFuture.completedFuture(PROTOCOL_ERROR);
                }
                final char side = (in.getByte(offset + 1) == SIDE_SELL_WIRE) ? 'S' : 'B';
                final int account = in.getInt(offset + 4, LE);
                final int security = in.getInt(offset + 8, LE);
                final int qty = in.getInt(offset + 12, LE);
                final long limitPx = in.getLong(offset + 16, LE);
                clOrdId = in.getLong(offset + 24, LE);
                clOrdIdOut[0] = clOrdId;
                final boolean sequenced = (in.getByte(offset + 2) & NEW_FLAG_SEQUENCED) != 0;
                if (sequenced && refuseSequenced) {
                    return CompletableFuture.completedFuture(PROTOCOL_ERROR);
                }
                if (sequenced && (clOrdId & 0xffffffffL) == 0L) {
                    return CompletableFuture.completedFuture(PROTOCOL_ERROR); // seq starts at 1
                }
                if (tRecvNanos != 0) {
                    metrics.recordDecode(System.nanoTime() - tRecvNanos);
                }
                return sequenced
                    ? submitter.submitSequencedOrderAsync(clOrdId, account, security, side, qty, limitPx,
                        clOrdId >>> 32, clOrdId & 0xffffffffL, completions)
                    : submitter.submitOrderAsync(clOrdId, account, security, side, qty, limitPx, completions);
            }
            case MSG_CANCEL: {
                if (len < 16) {
                    return CompletableFuture.completedFuture(PROTOCOL_ERROR);
                }
                final int orderRef = in.getInt(offset + 4, LE);
                clOrdIdOut[0] = in.getLong(offset + 8, LE);
                if (tRecvNanos != 0) {
                    metrics.recordDecode(System.nanoTime() - tRecvNanos);
                }
                return submitter.submitCancelAsync(orderRef, completions);
            }
            case MSG_REPLACE: {
                if (len < 32) {
                    return CompletableFuture.completedFuture(PROTOCOL_ERROR);
                }
                final int orderRef = in.getInt(offset + 4, LE);
                final int qty = in.getInt(offset + 8, LE);
                final long limitPx = in.getLong(offset + 16, LE);
                final long replaceClOrdId = in.getLong(offset + 24, LE);
                clOrdIdOut[0] = replaceClOrdId;
                if (tRecvNanos != 0) {
                    metrics.recordDecode(System.nanoTime() - tRecvNanos);
                }
                return submitter.submitReplaceAsync(orderRef, replaceClOrdId, qty, limitPx, completions);
            }
            default:
                return CompletableFuture.completedFuture(PROTOCOL_ERROR);
        }
    }

    /** Encode the ack for one frame's answer into {@code ackOut}; returns the bytes to write. */
    private static int ackFor(final MutableDirectBuffer ackOut, final OrderSubmitter.ExecResult r, final long clOrdId) {
        if (r == PROTOCOL_ERROR) {
            return writeAck(ackOut, STATUS_PROTOCOL_ERROR, (byte) 0, (byte) 0, 0, clOrdId);
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
