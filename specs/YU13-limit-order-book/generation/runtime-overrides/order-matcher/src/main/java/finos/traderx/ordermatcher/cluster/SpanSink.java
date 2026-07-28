package finos.traderx.ordermatcher.cluster;

import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OTEL-01: the asynchronous span sink. This is the class that has to earn the professor's
 * "integrate them in an asynchronous manner so you don't slow down the flow of trades", so the
 * design rule is stated up front and everything below serves it:
 *
 * <p><b>The trade path never waits for telemetry, and never allocates for it.</b> A producer
 * (a REST/FIX submit thread, the gateway owner thread, or a member's apply thread) does exactly one
 * thing: copy 8 longs into a pre-allocated {@link ManyToOneRingBuffer} and return. There is no lock,
 * no allocation, no I/O, no blocking queue, and — critically — <b>no backpressure path back to the
 * caller</b>. If the ring is full the write fails immediately, {@link #dropped} is incremented and
 * the order carries on untouched. Dropping telemetry under load is correct behaviour; stalling an
 * owner thread behind a slow collector would be the worst own-goal available to us, so the code
 * makes that outcome unreachable rather than unlikely.
 *
 * <p>Everything expensive — hex formatting, JSON assembly, HTTP, retries, the collector being down —
 * happens on ONE daemon thread that no order ever touches. A collector outage costs a counter, not a
 * millisecond: the exporter fails its POST, increments {@link #exportFailures}, and keeps draining.
 *
 * <p><b>Why not the OpenTelemetry SDK.</b> Its {@code BatchSpanProcessor} has the right shape (bounded
 * queue, drop on full), but the API above it allocates per span — {@code SdkSpan}, {@code Attributes},
 * String ids — on a path that is under an allocation gate and runs under Epsilon GC in the no-GC
 * proofs. We emit OTLP/HTTP with a JSON body instead, which is a documented, stable wire format and
 * costs us ~100 lines and zero new dependencies (Agrona and {@code java.net.http} are already here).
 * The interop is unchanged: this posts to the same {@code /v1/traces} endpoint any SDK would.
 *
 * <p>Created only when {@code OTEL_TRACES=1}; otherwise every call site holds a null reference and
 * pays a single predictable null check — which is also the "telemetry off" arm of the before/after
 * benchmark that has to prove this claim rather than assert it.
 */
public final class SpanSink implements AutoCloseable {

    /** One span = 8 longs: traceHi, traceLo, spanId, parentId, startNanos, endNanos, name, attr. */
    private static final int SPAN_LONGS = 8;
    private static final int SPAN_BYTES = SPAN_LONGS * Long.BYTES;
    private static final int MSG_TYPE_SPAN = 1;

    /** Span names, indexed by the ordinal written into the ring (an int on the hot path, never a
     *  String — the exporter thread does the lookup). */
    static final int NAME_ORDER = 0;
    static final int NAME_DECODE = 1;
    static final int NAME_QUEUE = 2;
    static final int NAME_CLUSTER = 3;
    static final int NAME_REPLY = 4;
    static final int NAME_COMMIT = 5;
    static final int NAME_APPLY = 6;
    private static final String[] NAMES = {
        "order", "gateway.decode", "gateway.queue", "cluster.consensus", "gateway.reply",
        "cluster.commit", "cluster.apply",
    };

    private final ManyToOneRingBuffer ring;
    private final String serviceName;
    private final URI endpoint;
    private final int batchLimit;
    private final HttpClient http;
    private final Thread exporter;

    // Racy-read counters (one writer each on the producer side is not true, so use atomics — they are
    // off the measured path in the disabled arm and cost a lock-free add in the enabled one).
    private final AtomicLong emitted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong exported = new AtomicLong();
    private final AtomicLong exportFailures = new AtomicLong();

    // Exporter-thread-only scratch, allocated once.
    private final StringBuilder json = new StringBuilder(1 << 16);
    private final long[] scratch = new long[SPAN_LONGS];
    private int batched;

    private volatile boolean running = true;

    private SpanSink(final String serviceName, final URI endpoint, final int capacityBytes,
                     final int batchLimit, final long flushMillis, final boolean startExporter) {
        this.serviceName = serviceName;
        this.endpoint = endpoint;
        this.batchLimit = batchLimit;
        this.ring = new ManyToOneRingBuffer(new UnsafeBuffer(
            ByteBuffer.allocateDirect(capacityBytes + RingBufferDescriptor.TRAILER_LENGTH)));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        if (startExporter) {
            this.exporter = new Thread(() -> exportLoop(flushMillis), "otel-span-exporter");
            this.exporter.setDaemon(true); // never holds shutdown open; in-ring spans are expendable
            this.exporter.start();
        } else {
            this.exporter = null;
        }
    }

    /** Test seam: no exporter thread, so a test can fill the ring on purpose and drain it by hand. */
    static SpanSink forTest(final int capacityBytes) {
        return new SpanSink("test", URI.create("http://localhost:1/v1/traces"), capacityBytes,
            512, 1000L, false);
    }

    /** Test seam: render whatever is currently in the ring, or null if it was empty. */
    String drainOnce() {
        json.setLength(0);
        batched = 0;
        ring.read(this::onSpan, batchLimit);
        return batched > 0 ? finishBody() : null;
    }

    long droppedCount() {
        return dropped.get();
    }

    long emittedCount() {
        return emitted.get();
    }

    /**
     * Build from env, or null when {@code OTEL_TRACES} is unset/0 — the zero-overhead arm.
     *
     * <p>{@code OTEL_ENDPOINT} defaults to the in-cluster collector's OTLP/HTTP port. The collector
     * is deliberately an ordinary namespace workload: members run on tainted, core-pinned nodes and
     * nothing observability-related is ever scheduled there, so this is always a network hop off the
     * member node — which is exactly what we want it to be.
     */
    static SpanSink fromEnvOrNull(final String serviceName) {
        final String on = System.getenv("OTEL_TRACES");
        if (on == null || on.isEmpty() || on.equals("0")) {
            return null;
        }
        final String base = envOr("OTEL_ENDPOINT", "http://otel-collector:4318");
        final int capacity = Integer.parseInt(envOr("OTEL_RING_BYTES", String.valueOf(1 << 20)));
        final int batch = Integer.parseInt(envOr("OTEL_BATCH_SPANS", "512"));
        final long flush = Long.parseLong(envOr("OTEL_FLUSH_MS", "1000"));
        return new SpanSink(serviceName, URI.create(base + "/v1/traces"), capacity, batch, flush, true);
    }

    /** Head-sampling mask, shared by gateway and member so their verdicts agree (see
     *  {@link OrderTrace}). 127 = 1 in 128; 0 traces every order. */
    static int sampleMaskFromEnv() {
        return Integer.parseInt(envOr("OTEL_SAMPLE_MASK", "127"));
    }

    private static String envOr(final String name, final String fallback) {
        final String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }

    /**
     * THE HOT-PATH ENTRY POINT. Copies 8 longs and returns; never blocks, never allocates, never
     * throws. A false return from {@code ring.write} means the exporter is behind and this span is
     * discarded — counted, not queued, and never pushed back at the caller.
     */
    void span(final long traceHi, final long traceLo, final long spanId, final long parentId,
              final long startEpochNanos, final long endEpochNanos, final int nameOrdinal,
              final long attr) {
        final int index = ring.tryClaim(MSG_TYPE_SPAN, SPAN_BYTES);
        if (index <= 0) {
            dropped.incrementAndGet();
            return;
        }
        final org.agrona.MutableDirectBuffer buffer = ring.buffer();
        buffer.putLong(index, traceHi);
        buffer.putLong(index + 8, traceLo);
        buffer.putLong(index + 16, spanId);
        buffer.putLong(index + 24, parentId);
        buffer.putLong(index + 32, startEpochNanos);
        buffer.putLong(index + 40, endEpochNanos);
        buffer.putLong(index + 48, nameOrdinal);
        buffer.putLong(index + 56, attr);
        ring.commit(index);
        emitted.incrementAndGet();
    }

    /** Prometheus lines for the sink itself — "what is dropping" is a first-class support question,
     *  so the drop counter is exported next to the business counters rather than hidden in a log. */
    String metrics() {
        return "traderx_otel_spans_total{outcome=\"emitted\"} " + emitted.get() + "\n"
            + "traderx_otel_spans_total{outcome=\"dropped\"} " + dropped.get() + "\n"
            + "traderx_otel_spans_total{outcome=\"exported\"} " + exported.get() + "\n"
            + "traderx_otel_export_failures_total " + exportFailures.get() + "\n";
    }

    // ----- exporter thread ---------------------------------------------------------------------

    private void exportLoop(final long flushMillis) {
        while (running) {
            try {
                json.setLength(0);
                batched = 0;
                ring.read(this::onSpan, batchLimit);
                if (batched > 0) {
                    post(finishBody());
                } else {
                    Thread.sleep(flushMillis);
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (final Exception e) {
                exportFailures.incrementAndGet(); // collector down / malformed: never fatal
            }
        }
    }

    /** Ring consumer: append one span's JSON. Runs only on the exporter thread. */
    @SuppressWarnings("unused") // msgTypeId is part of the MessageHandler contract
    private void onSpan(final int msgTypeId, final org.agrona.MutableDirectBuffer buffer,
                        final int index, final int length) {
        if (msgTypeId != MSG_TYPE_SPAN || length != SPAN_BYTES) {
            return;
        }
        for (int i = 0; i < SPAN_LONGS; i++) {
            scratch[i] = buffer.getLong(index + (i * 8));
        }
        if (batched == 0) {
            startBody();
        } else {
            json.append(',');
        }
        batched++;
        json.append("{\"traceId\":\"");
        appendHex16(scratch[0]);
        appendHex16(scratch[1]);
        json.append("\",\"spanId\":\"");
        appendHex16(scratch[2]);
        json.append('"');
        if (scratch[3] != 0L) {
            json.append(",\"parentSpanId\":\"");
            appendHex16(scratch[3]);
            json.append('"');
        }
        json.append(",\"name\":\"").append(NAMES[(int) scratch[6]]).append('"')
            .append(",\"kind\":2")   // SPAN_KIND_SERVER
            .append(",\"startTimeUnixNano\":\"").append(scratch[4]).append('"')
            .append(",\"endTimeUnixNano\":\"").append(scratch[5]).append('"')
            .append(",\"attributes\":[{\"key\":\"traderx.order_ref\",\"value\":{\"intValue\":\"")
            .append(scratch[7]).append("\"}}]}");
    }

    private void startBody() {
        json.append("{\"resourceSpans\":[{\"resource\":{\"attributes\":[")
            .append("{\"key\":\"service.name\",\"value\":{\"stringValue\":\"")
            .append(serviceName).append("\"}}]},")
            .append("\"scopeSpans\":[{\"scope\":{\"name\":\"traderx\"},\"spans\":[");
    }

    private String finishBody() {
        json.append("]}]}]}");
        return json.toString();
    }

    private void post(final String body) throws Exception {
        final HttpRequest request = HttpRequest.newBuilder(endpoint)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        final HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() / 100 == 2) {
            exported.addAndGet(batched);
        } else {
            exportFailures.incrementAndGet();
        }
    }

    /** Lower-case hex, exactly 16 chars, zero-padded — the W3C/OTLP id encoding. */
    private void appendHex16(final long v) {
        for (int shift = 60; shift >= 0; shift -= 4) {
            json.append(Character.forDigit((int) ((v >>> shift) & 0xF), 16));
        }
    }

    @Override
    public void close() {
        running = false;
        if (exporter != null) {
            exporter.interrupt();
        }
    }
}
