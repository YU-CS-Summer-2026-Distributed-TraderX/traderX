package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.JetStream;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.PushSubscribeOptions;
import io.nats.client.Subscription;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * YU15 EOD risk-extract producer: turns the completion of the overnight P&L batch into one
 * immutable, byte-reproducible portfolio fixture for the pricing/risk engine.
 *
 * <p>The whole job in order:
 * <ol>
 *   <li>wait on {@code eod.pnl.done} (JetStream durable) — not {@code eod.prices.ready}, because
 *       the later event is the one that guarantees both that closing prices are final and that our
 *       own P&amp;L already exists as the consumer's reconciliation target;</li>
 *   <li>offer a sequenced risk-extract marker to the cluster and take its ack's sequence as N;</li>
 *   <li>receive the position cut the leader rendered at N;</li>
 *   <li>offer a second marker and require it landed at exactly N+1 — the consensus log is its own
 *       witness that nothing traded while the extract was being built (FR-RXT08);</li>
 *   <li>join the cut with the YU06 published closes and the counterparty reference data;</li>
 *   <li>write the fixture write-once, and announce it on {@code risk.extract.ready}.</li>
 * </ol>
 *
 * <p>Positions never come from the SQL read model. That model is asynchronous, so every account
 * would be sampled at a slightly different instant and the resulting VaR would be computed on a
 * portfolio that never simultaneously existed. Marks do come from the database, but only from
 * {@code eod_price_snapshot}, which is immutable once published and addressed by
 * {@code (session_date, version)} — reading it is a lookup in a frozen table, not a race.
 */
public final class RiskExtractMain {

    private static final long ACK_TIMEOUT_MS = 30_000;
    private static final long CUT_TIMEOUT_MS = 60_000;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final UnsafeBuffer markerBuffer =
        new UnsafeBuffer(new byte[AeronReplicationCodec.RISK_EXTRACT_BYTES]);

    private AeronCluster client;
    private String aeronDir;
    private volatile long[] lastExtractAck; // {seq, rows, requestId}
    private long nextRequestId = 1;

    public static void main(final String[] args) throws Exception {
        if (args.length >= 3 && "--rebuild".equals(args[0])) {
            // Acceptance path (FR-RXT10): rebuild the fixture from a stored cut and nothing else.
            // If this does not reproduce the original bytes, the fixture is not reproducible.
            // A 4th argument rebuilds the YU17 contracts artifact from that SAME cut, which is what
            // makes "two artifacts from one cut" checkable rather than merely asserted.
            rebuild(Path.of(args[1]), Path.of(args[2]),
                args.length >= 4 ? Path.of(args[3]) : null);
            return;
        }
        try {
            new RiskExtractMain().run();
        } catch (final Throwable t) {
            // The media-driver and NATS threads are non-daemon, so main dying does NOT end the
            // JVM: PID 1 stays alive and the pod reports Ready while the producer no longer
            // exists — the silent-death defect of 2026-08-13. PID 1 is java itself (no wrapper),
            // so process exit IS the liveness signal Kubernetes watches; make death loud here.
            // halt, not exit: a shutdown hook blocked on the same broken dependency that killed
            // us would reproduce exactly the wedge this exists to prevent.
            System.err.println("RISK-EXTRACT-DEAD producer failed; exiting so Kubernetes sees it");
            t.printStackTrace();
            Runtime.getRuntime().halt(1);
        }
    }

    // ----- rebuild ----------------------------------------------------------------------------

    private static void rebuild(final Path cutFile, final Path outFile, final Path contractsFile)
            throws Exception {
        final String cut = Files.readString(cutFile, StandardCharsets.US_ASCII);
        final Map<Integer, RiskExtractCsv.Counterparty> accounts = loadCounterparties();
        final RiskExtractCsv.Stamp stamp = stampOf(cut);
        final String rendered = RiskExtractCsv.render(cut, loadMarksFromEnv(cut),
            accounts, loadBondStatics(), stamp);
        Files.writeString(outFile, rendered, StandardCharsets.US_ASCII);
        System.out.println("RISK-EXTRACT-REBUILD out=" + outFile + " bytes=" + rendered.length());
        if (contractsFile != null) {
            final String contracts = SwapContractCsv.render(cut, accounts, stamp);
            Files.writeString(contractsFile, contracts, StandardCharsets.US_ASCII);
            System.out.println("RISK-EXTRACT-REBUILD out=" + contractsFile
                + " bytes=" + contracts.length());
        }
    }

    // ----- main loop --------------------------------------------------------------------------

    private void run() throws Exception {
        final String natsUrl = env("RISK_EXTRACT_NATS_URL", "nats://localhost:4222");
        final String cutSubject = env("RISK_EXTRACT_CUT_SUBJECT", "risk.extract.cut");
        final String readySubject = env("RISK_EXTRACT_READY_SUBJECT", "risk.extract.ready");
        final String stream = env("EOD_STREAM", "TRADERX_EOD");
        final String pnlDone = env("EOD_PNL_DONE_SUBJECT", "eod.pnl.done");
        final String durable = env("RISK_EXTRACT_DURABLE", "risk-extract");
        // The other subject on the shared YU06 stream. We never consume it, but the stream must
        // carry it or trade-processor's gate event has nowhere to land.
        final String pricesReady = env("EOD_PRICES_READY_SUBJECT", "eod.prices.ready");

        final String aeronDir = env("RISK_EXTRACT_AERON_DIR", "/dev/shm/aeron-risk-extract");
        final MediaDriver driver = MediaDriver.launch(new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .threadingMode(ThreadingMode.SHARED)
            .termBufferSparseFile(true)
            .dirDeleteOnStart(true));

        this.aeronDir = aeronDir;
        final Connection nats = connectNats(natsUrl);

        ensureStream(nats, stream, pricesReady, pnlDone);
        final JetStream js = nats.jetStream();
        final Dispatcher dispatcher = nats.createDispatcher();
        // The previous pod's binding to the durable can outlive it: the server clears interest
        // only once it notices the dead client's disconnect, and a rolling replacement overlaps
        // pods outright. That is a self-healing condition, not an error — wait it out
        // ([SUB-90012] "Consumer is already bound to a subscription"). Anything else still throws.
        // BOUNDED. Waiting out a lingering binding is right; waiting forever is the bug this class
        // was just fixed for, reached by a different road. Nothing throws while the loop spins, so
        // the halt() above never fires, and this Deployment carries no readiness probe — so an
        // unbounded retry leaves the pod Running and Ready with the producer permanently absent
        // and the EOD extract silently never running. That is exactly the state the halt() exists
        // to prevent.
        //
        // The ordinary case clears in seconds (the server drops the dead client's interest, and
        // strategy: Recreate means a rollout no longer overlaps pods at all), so this deadline is
        // only reached when the durable is genuinely stuck — a ghost consumer, or a second replica
        // someone scaled up by hand. Falling through to the outer catch turns "retrying silently
        // forever" into a container exit, and a persistent one into CrashLoopBackOff, which is
        // visible in `kubectl get pods` and alertable. Loud and dead beats quiet and dead.
        final long bindDeadline = System.currentTimeMillis()
            + 1000L * Long.parseLong(env("RISK_EXTRACT_BIND_TIMEOUT_S", "300"));
        int bindAttempts = 0;
        while (true) {
            try {
                js.subscribe(pnlDone, dispatcher, msg -> onPnlDone(nats, msg, cutSubject, readySubject),
                    false, PushSubscribeOptions.builder().stream(stream).durable(durable).build());
                break;
            } catch (final IllegalArgumentException e) {
                if (!String.valueOf(e.getMessage()).contains("[SUB-90012]")) {
                    throw e;
                }
                if (System.currentTimeMillis() >= bindDeadline) {
                    throw new IllegalStateException("durable '" + durable + "' was still bound after "
                        + bindAttempts + " attempts; giving up so this pod dies visibly rather than"
                        + " sitting Ready with no producer. Something else is holding it — check for"
                        + " a second risk-extract replica or a ghost consumer on the stream.", e);
                }
                bindAttempts++;
                System.out.println("RISK-EXTRACT durable '" + durable
                    + "' still bound to the previous pod; retrying (attempt " + bindAttempts
                    + ", giving up in " + ((bindDeadline - System.currentTimeMillis()) / 1000) + "s): "
                    + e.getMessage());
                Thread.sleep(2000);
            }
        }

        System.out.println("RISK-EXTRACT up: nats=" + natsUrl + " trigger=" + pnlDone
            + " durable=" + durable + " sink=" + env("RISK_EXTRACT_SINK_URI", "<unset>"));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            CloseHelper.quietCloseAll(client, driver);
            try {
                nats.close();
            } catch (final InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        }));
        Thread.currentThread().join();
    }

    /**
     * Idempotently ensure the EOD stream exists, exactly as position-service's consumer does at
     * its end. Whichever side starts first creates it; neither has to be started first.
     *
     * <p>It must declare the WHOLE subject family, not just the one subject this process consumes.
     * The stream is shared with the YU06 chain, and whichever side creates it fixes its subject
     * list — so a stream created here carrying only {@code eod.pnl.done} leaves trade-processor's
     * {@code eod.prices.ready} publish with no responder, silently breaking the batch chain
     * upstream of us. An already-existing stream missing a subject is repaired rather than
     * tolerated, since that is exactly the damage an earlier incomplete create leaves behind.
     */
    private static void ensureStream(final Connection nats, final String stream,
                                     final String... subjects) throws Exception {
        final io.nats.client.JetStreamManagement jsm = nats.jetStreamManagement();
        try {
            final io.nats.client.api.StreamInfo info = jsm.getStreamInfo(stream);
            final java.util.List<String> existing = info.getConfiguration().getSubjects();
            final java.util.List<String> merged = new java.util.ArrayList<>(existing);
            for (final String subject : subjects) {
                if (!merged.contains(subject)) {
                    merged.add(subject);
                }
            }
            if (merged.size() != existing.size()) {
                jsm.updateStream(io.nats.client.api.StreamConfiguration.builder(info.getConfiguration())
                    .subjects(merged)
                    .build());
                System.out.println("RISK-EXTRACT repaired stream " + stream
                    + " subjects=" + existing + " -> " + merged);
            }
            return;
        } catch (final io.nats.client.JetStreamApiException e) {
            if (e.getApiErrorCode() != 10059) { // 10059 = stream not found
                throw e;
            }
        }
        jsm.addStream(io.nats.client.api.StreamConfiguration.builder()
            .name(stream)
            .subjects(subjects)
            .storageType(io.nats.client.api.StorageType.File)
            .build());
        System.out.println("RISK-EXTRACT created stream " + stream
            + " subjects=" + java.util.Arrays.toString(subjects));
    }

    /**
     * A once-a-day batch producer must not die because a dependency was slow to come up — it would
     * simply not exist when the batch fires. Retry forever; the pod is useless until NATS is there.
     */
    private static Connection connectNats(final String url) throws InterruptedException {
        while (true) {
            try {
                return Nats.connect(new Options.Builder()
                    .server(url).connectionTimeout(Duration.ofSeconds(10)).maxReconnects(-1).build());
            } catch (final Exception e) {
                System.out.println("RISK-EXTRACT waiting for NATS at " + url + ": " + e);
                Thread.sleep(2000);
            }
        }
    }

    /**
     * One EOD batch. Any failure leaves the message unacked so JetStream redelivers: the extract is
     * addressed by {@code (sessionDate, version, sequence)} and written write-once, so a retry
     * either produces a new sequence or refuses to clobber — never a half-written fixture.
     */
    private void onPnlDone(final Connection nats, final Message msg, final String cutSubject,
                           final String readySubject) {
        try {
            final JSONObject event = new JSONObject(new String(msg.getData(), StandardCharsets.UTF_8));
            final LocalDate sessionDate = LocalDate.parse(event.getString("sessionDate"));
            final int version = event.getInt("version");
            System.out.println("RISK-EXTRACT trigger sessionDate=" + sessionDate + " version=" + version);

            // A fresh cluster session per batch: a session opened at startup would be hours stale
            // (and possibly pointing at a former leader) by the time the batch actually fires.
            // AeronCluster.connect finds the current leader from the endpoint list on its own.
            connectCluster(aeronDir);
            // Subscribe BEFORE the marker so the cut cannot arrive before we are listening.
            final Subscription cutSub = nats.subscribe(cutSubject);
            try {
                final long[] first = mark(sessionDate, version);
                final String cut = awaitCut(cutSub, first[0]);

                final Map<String, RiskExtractCsv.Mark> marks = loadMarks(sessionDate, version);
                final Map<Integer, RiskExtractCsv.Counterparty> accounts = loadCounterparties();

                final long[] witness = mark(sessionDate, version);
                if (witness[0] != first[0] + 1) {
                    // The market is closed; anything sequenced between the two markers means it
                    // was not, and the cut no longer describes the portfolio being reported.
                    throw new IllegalStateException("risk extract: cluster was not quiescent — "
                        + "marker sequences " + first[0] + " and " + witness[0]
                        + " differ by more than one; refusing to emit");
                }

                final RiskExtractCsv.Stamp stamp = stampOf(cut);
                final String extract = RiskExtractCsv.render(cut, marks, accounts, loadBondStatics(), stamp);
                // YU17 (D3): the second artifact, rendered from the SAME cut under the SAME stamp.
                // Both are always written — a consumer that finds no contracts file cannot tell an
                // empty book of swaps from a producer that does not render them.
                final String contracts = SwapContractCsv.render(cut, accounts, stamp);
                final String[] uris = write(sessionDate, version, stamp.consensusSequence(),
                    cut, extract, contracts);

                final String payload = new JSONObject()
                    .put("schema", RiskExtractCsv.SCHEMA)
                    .put("uri", uris[0])
                    .put("consensusSequence", stamp.consensusSequence())
                    .put("sessionDate", sessionDate.toString())
                    .put("priceSnapshotVersion", version)
                    .put("rows", extract.lines().filter(l -> !l.startsWith("#")).count() - 1)
                    .put("sha256", RiskExtractCut.sha256(extract))
                    .put("cutSha256", stamp.cutSha256())
                    .put("quiesceWitnessSequence", witness[0])
                    // The contracts artifact shares consensusSequence, sessionDate and cutSha256
                    // with the netted one above: one cut, one instant, two files.
                    .put("contractsSchema", SwapContractCsv.SCHEMA)
                    .put("contractsUri", uris[1])
                    .put("contracts", contracts.lines().filter(l -> !l.startsWith("#")).count() - 1)
                    .put("contractsSha256", RiskExtractCut.sha256(contracts))
                    .toString();
                nats.publish(readySubject, payload.getBytes(StandardCharsets.UTF_8));
                nats.flush(Duration.ofSeconds(5));
                System.out.println("RISK-EXTRACT-READY " + payload);
                msg.ack();
            } finally {
                cutSub.unsubscribe();
                CloseHelper.quietClose(client);
                client = null;
            }
        } catch (final Exception ex) {
            // No ack: JetStream redelivers. Nothing partial was published or announced.
            System.err.println("RISK-EXTRACT-FAILED " + ex);
            ex.printStackTrace();
        }
    }

    // ----- cluster ----------------------------------------------------------------------------

    private void connectCluster(final String aeronDir) {
        final String endpoints = env("CLUSTER_INGRESS_ENDPOINTS", "0=localhost:21802");
        CloseHelper.quietClose(client);
        client = AeronCluster.connect(new AeronCluster.Context()
            .aeronDirectoryName(aeronDir)
            .ingressChannel("aeron:udp?term-length=64k")
            .ingressEndpoints(endpoints)
            .egressChannel("aeron:udp?term-length=64k|endpoint="
                + env("RISK_EXTRACT_EGRESS_HOST", env("POD_IP", "localhost")) + ":"
                + env("RISK_EXTRACT_EGRESS_PORT", "0"))
            .egressListener(this::onEgress));
    }

    /** Offer one sequenced marker and return its ack as {@code {sequence, rowCount}}. */
    private long[] mark(final LocalDate sessionDate, final int version) {
        final long requestId = nextRequestId++;
        codec.encodeRiskExtract(markerBuffer, 0, requestId, sessionDate.toEpochDay(), version);
        lastExtractAck = null;
        final long deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS;
        boolean offered = false;
        while (System.currentTimeMillis() < deadline) {
            client.pollEgress();
            if (!offered && client.offer(markerBuffer, 0, AeronReplicationCodec.RISK_EXTRACT_BYTES) > 0) {
                offered = true;
            }
            final long[] ack = lastExtractAck;
            if (offered && ack != null && ack[2] == requestId) {
                return new long[] { ack[0], ack[1] };
            }
            Thread.yield();
        }
        throw new IllegalStateException("risk extract: no marker ack within " + ACK_TIMEOUT_MS + "ms");
    }

    private void onEgress(final long clusterSessionId, final long timestamp, final DirectBuffer buffer,
                          final int offset, final int length, final io.aeron.logbuffer.Header header) {
        if (buffer.getByte(offset + 12) == MatchingEngineClusteredService.KIND_RISK_EXTRACT_MARKED) {
            lastExtractAck = new long[] {
                buffer.getLong(offset), buffer.getInt(offset + 8), buffer.getLong(offset + 13) };
        }
    }

    /** Wait for the cut the leader rendered at {@code seq}, ignoring any other cut on the wire. */
    private String awaitCut(final Subscription sub, final long seq) throws InterruptedException {
        final String want = "seq=" + seq + " ";
        final long deadline = System.currentTimeMillis() + CUT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            final Message m = sub.nextMessage(Duration.ofSeconds(2));
            if (m == null) {
                continue;
            }
            final String cut = new String(m.getData(), StandardCharsets.US_ASCII);
            if (cut.startsWith("#cut ") && cut.contains(want)) {
                return cut;
            }
        }
        throw new IllegalStateException("risk extract: no cut for sequence " + seq
            + " within " + CUT_TIMEOUT_MS + "ms (is RISK_EXTRACT_NATS_URL set on the cluster?)");
    }

    // ----- inputs -----------------------------------------------------------------------------

    private static RiskExtractCsv.Stamp stampOf(final String cut) {
        final String head = cut.substring(0, cut.indexOf('\n'));
        return new RiskExtractCsv.Stamp(
            Long.parseLong(headerField(head, "seq=")),
            LocalDate.ofEpochDay(Long.parseLong(headerField(head, "sessionDateEpochDay="))),
            Integer.parseInt(headerField(head, "priceVersion=")),
            RiskExtractCut.sha256(cut));
    }

    private static String headerField(final String head, final String key) {
        final int from = head.indexOf(key) + key.length();
        int to = head.indexOf(' ', from);
        if (to < 0) {
            to = head.length();
        }
        return head.substring(from, to);
    }

    private static Map<String, RiskExtractCsv.Mark> loadMarksFromEnv(final String cut) throws Exception {
        final String head = cut.substring(0, cut.indexOf('\n'));
        return loadMarks(LocalDate.ofEpochDay(Long.parseLong(headerField(head, "sessionDateEpochDay="))),
            Integer.parseInt(headerField(head, "priceVersion=")));
    }

    /**
     * The YU06 published closes for exactly this {@code (session_date, version)}. That row set is
     * immutable once published — a correction is a new version — so this read is reproducible and
     * carries no consistency hazard of its own.
     */
    private static Map<String, RiskExtractCsv.Mark> loadMarks(final LocalDate sessionDate,
                                                              final int version) throws Exception {
        final Map<String, RiskExtractCsv.Mark> marks = new HashMap<>();
        final String url = env("RISK_EXTRACT_JDBC_URL", "");
        if (url.isEmpty()) {
            // No price database wired in: every row falls back to the engine's last trade at N,
            // which the fixture records per row as its mark source.
            return marks;
        }
        try (java.sql.Connection db = java.sql.DriverManager.getConnection(url,
                env("RISK_EXTRACT_JDBC_USER", "traderx"), env("RISK_EXTRACT_JDBC_PASSWORD", ""));
             java.sql.PreparedStatement ps = db.prepareStatement(
                 "SELECT s.security, s.closing_price, s.quality FROM eod_price_snapshot s"
                     + " JOIN eod_price_session h ON h.session_date = s.session_date"
                     + " AND h.version = s.version"
                     + " WHERE s.session_date = ? AND s.version = ? AND h.status = 'PUBLISHED'")) {
            ps.setObject(1, sessionDate);
            ps.setInt(2, version);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final BigDecimal price = rs.getBigDecimal("closing_price");
                    final String quality = rs.getString("quality");
                    if (price != null && !"MISSING".equals(quality)) {
                        marks.put(rs.getString("security"), new RiskExtractCsv.Mark(price, quality));
                    }
                }
            }
        }
        return marks;
    }

    /** counterparties.csv — {@code accountId,counterpartyId,nettingSetId,currency}. */
    private static Map<Integer, RiskExtractCsv.Counterparty> loadCounterparties() throws Exception {
        final Path file = Path.of(env("RISK_EXTRACT_REFERENCE_DATA", "/opt/app/classes/reference-data"))
            .resolve("counterparties.csv");
        final Map<Integer, RiskExtractCsv.Counterparty> out = new HashMap<>();
        final List<String> lines = Files.readAllLines(file, StandardCharsets.US_ASCII);
        for (int i = 1; i < lines.size(); i++) {
            final String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            final String[] c = line.split(",", -1);
            if (c.length != 4) {
                throw new IllegalStateException("counterparties.csv: malformed row: " + line);
            }
            out.put(Integer.parseInt(c[0]), new RiskExtractCsv.Counterparty(c[1], c[2], c[3]));
        }
        return out;
    }

    /**
     * instruments.csv (YU16, ADR-059) — bond static for the extract join, parsed by HEADER NAME
     * so column additions never shift a hardcoded index. A row of type {@code treasury} missing
     * its coupon or maturity is malformed and aborts the extract (the inherited unmappable-row
     * rule): misclassifying a bond as an equity would be a silent 100x story downstream.
     */
    private static Map<String, RiskExtractCsv.BondStatic> loadBondStatics() throws Exception {
        final Path file = Path.of(env("RISK_EXTRACT_REFERENCE_DATA", "/opt/app/classes/reference-data"))
            .resolve("instruments.csv");
        final List<String> lines = Files.readAllLines(file, StandardCharsets.US_ASCII);
        final Map<String, RiskExtractCsv.BondStatic> out = new HashMap<>();
        if (lines.isEmpty()) {
            return out;
        }
        final List<String> header = List.of(lines.get(0).split(",", -1));
        final int tickerAt = header.indexOf("ticker");
        final int typeAt = header.indexOf("type");
        final int couponAt = header.indexOf("couponRatePercent");
        final int maturityAt = header.indexOf("maturityDate");
        if (tickerAt < 0 || typeAt < 0) {
            throw new IllegalStateException("instruments.csv: header missing ticker/type columns");
        }
        for (int i = 1; i < lines.size(); i++) {
            final String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            final String[] c = line.split(",", -1);
            if (!"treasury".equals(c[typeAt])) {
                continue;
            }
            if (couponAt < 0 || maturityAt < 0
                || c[couponAt].isEmpty() || c[maturityAt].isEmpty()) {
                throw new IllegalStateException("instruments.csv: treasury row missing coupon/maturity: " + line);
            }
            out.put(c[tickerAt], new RiskExtractCsv.BondStatic(c[couponAt], c[maturityAt]));
        }
        return out;
    }

    // ----- delivery ---------------------------------------------------------------------------

    /**
     * Write the cut and the fixture write-once under {@code <sink>/<sessionDate>/v<version>/}.
     * The cut is kept beside the fixture so the fixture can be rebuilt from it and byte-compared
     * without the cluster being involved at all.
     */
    private static String[] write(final LocalDate sessionDate, final int version, final long seq,
                                  final String cut, final String extract, final String contracts)
            throws Exception {
        final String sink = env("RISK_EXTRACT_SINK_URI", "file:///data/risk-extracts");
        final String key = sessionDate + "/v" + version + "/seq-" + seq;
        if (sink.startsWith("gs://")) {
            return RiskExtractGcsSink.put(sink, key, cut, extract, contracts);
        }
        final Path dir = Path.of(java.net.URI.create(sink)).resolve(sessionDate.toString())
            .resolve("v" + version);
        Files.createDirectories(dir);
        // CREATE_NEW: an object is immutable once written; a retry must never silently replace one.
        Files.writeString(dir.resolve("seq-" + seq + ".cut"), cut, StandardCharsets.US_ASCII,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        final Path out = dir.resolve("seq-" + seq + ".csv");
        Files.writeString(out, extract, StandardCharsets.US_ASCII,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        // Same directory, same sequence, same write-once rule — a sibling of the netted fixture
        // rather than a separate delivery, because they are two renderings of one cut (D3).
        final Path contractsOut = dir.resolve("seq-" + seq + "-contracts.csv");
        Files.writeString(contractsOut, contracts, StandardCharsets.US_ASCII,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new String[] { out.toUri().toString(), contractsOut.toUri().toString() };
    }

    private static String env(final String key, final String fallback) {
        final String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
