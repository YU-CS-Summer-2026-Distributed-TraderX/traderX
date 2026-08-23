package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.Px;
import finos.traderx.ordermatcher.lmax.SwapConventions;
import finos.traderx.ordermatcher.lmax.TradeBlotter;
import finos.traderx.ordermatcher.model.OrderSide;
import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Image;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.RecordingLog;
import io.aeron.cluster.codecs.MessageHeaderDecoder;
import io.aeron.cluster.codecs.SessionMessageHeaderDecoder;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.YieldingIdleStrategy;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * YU05's reconciliation and regulatory-audit contract (FR-PTC04/05/10/20/21), served on the Aeron
 * Cluster tier from the tier's own source of truth.
 *
 * <h2>Why the archive and not a retained trade list</h2>
 *
 * <p>This is the CQRS integrity check: it reconciles the SOURCE OF TRUTH against the SQL
 * projection. Serving those trades from the projection compares SQL against itself and passes
 * vacuously (matched=0, nothing wrong reported) — a failure this suite has produced once already.
 * On this tier the authority is the replicated log, so the trades come from the members.
 *
 * <p>Retaining a trade list inside {@link MatchingEngineClusteredService} was the obvious
 * alternative and it is WRONG, not merely expensive. {@code ReconciliationService.runOrphanSweep()}
 * flags every local trade id ABSENT from the full-history index as {@code ORPHAN_IN_PROJECTION}, so
 * a bounded list turns every trade older than its bound into a FALSE orphan, and an unbounded one
 * is new replicated state that every snapshot pays for, forever. There is no bound that is both
 * safe and correct. It could not serve {@code /regulatory/report} at all — that reports order
 * accept/reject/cancel/fill events over an input-sequence range, which no trade list holds.
 *
 * <p>So this mirrors what the Spring tier already does ({@code LmaxEngine.reindexFullHistory()} /
 * {@code generateRegulatoryReport()}): replay the whole journal into a SHADOW engine and throw it
 * away. The cluster tier's journal is the Aeron Archive's cluster-log recording, and the shadow is
 * a second instance of the very same {@link MatchingEngineClusteredService} — reusing the real
 * apply path rather than reimplementing it, so the replay's orderRef generator, applied-sequence,
 * symbol-id assignment and cluster-time conversion cannot drift from the live one.
 *
 * <p><b>Retention bound = the archive's own log retention.</b> Not a count and not an age: the
 * snapshot trigger in {@link ClusterNodeMain} deliberately does not purge log segments (recovery is
 * latest-snapshot + tail; purging is a separate, explicit ops action), so "full history" is
 * genuinely full for the epoch. Nothing here is replicated state, nothing here enters the snapshot,
 * and {@code SNAPSHOT_FORMAT} is untouched — the live blotter and the full-history index have
 * exactly the status of the NATS bridges and the kdb tap already sitting in the same drain loop:
 * read-side projections of committed output, rebuilt by replay.
 *
 * <h2>Bounds that do exist</h2>
 *
 * <p>The live forward blotter is a bounded window ({@code RECON_BLOTTER_CAPACITY}) exactly like the
 * Spring tier's. The full-history index and the audit report are bounded by {@code
 * RECON_FULL_HISTORY_MAX} / {@code REGULATORY_MAX_RECORDS} and REFUSE past them rather than
 * truncate: a silently truncated index manufactures orphans, which is the failure this class exists
 * to prevent. A member is a consensus participant, so an admin query must not be able to OOM it.
 *
 * <h2>YU17: OTC contracts on the audit surface</h2>
 *
 * <p>The audit report was a YU05 projection over the ORDER lifecycle, written before an instrument
 * class existed that has no orders at all. A booked swap consumed a consensus sequence, mutated
 * replicated state and returned {@code booked:true}, and the report over that very sequence was
 * empty — an audit surface silently omitting an entire instrument class. It now carries them.
 *
 * <p><b>Read-side only, but not because an event was already going past.</b> A booking is a
 * committed log entry ({@code TYPE_SWAP_BOOK} / {@code TYPE_SWAPTION_BOOK}) and
 * {@code MatchingEngineClusteredService.onSwapBook} answers it with a DIRECT egress ack — it never
 * offers to the output ring, so no {@code OutputEvent} exists for the tap above to have missed.
 * What makes this a read-side change anyway is that the shadow replay already APPLIES those
 * bookings: the contract store is rebuilt in full by the same replay this class already runs, and
 * the projection was simply reading the shadow's output ring and never its contract store. So
 * nothing new is emitted, nothing enters the snapshot and {@code SNAPSHOT_FORMAT} is untouched —
 * the same status as everything else here.
 *
 * <p><b>Contract grain, never position grain.</b> A receive-fixed and a pay-fixed at equal notional
 * net to ZERO at position grain, destroying both rates; that is why swaps are carried per contract
 * and why they never reach the position model. This surface inherits that: one row per booked
 * contract, carrying its own direction and its own rate. {@code scripts/proofs/yu17-swap-netting.sh}
 * step 7 is the standing guard, and giving OTC a position row here would reintroduce exactly the
 * bug it was written to catch.
 */
final class ClusterRecon {
    /** Cluster log framing: our SBE payload starts after the cluster's own session header. */
    private static final int SESSION_HEADER_LENGTH =
        MessageHeaderDecoder.ENCODED_LENGTH + SessionMessageHeaderDecoder.BLOCK_LENGTH;
    private static final String IPC = "aeron:ipc";
    /** Must match {@code ClusterNodeConfig}'s {@code localControlChannel} verbatim: this is the
     *  publication side of the archive's own local control subscription. */
    private static final String ARCHIVE_LOCAL_CONTROL = "aeron:ipc?term-length=64k";
    private static final int CONTROL_RESPONSE_STREAM_ID = 20_777;
    private static final int REPLAY_STREAM_ID = 20_778;
    private static final long REPLAY_DEADLINE_MS = 10 * 60 * 1000L;

    /** One audit-trail row. Field names mirror the Spring tier's {@code AuditRecord} exactly, so a
     *  proof written against one tier reads the other without being told which it is talking to.
     *  The IDs are this tier's: {@code <epoch>-<orderRef>} and {@code <tradeSeq>-<B|S>}.
     *
     *  <p>YU17 reuses these ten columns for an OTC booking rather than widening the record: a new
     *  component would appear as a null on every one of the thousands of ORDER rows, changing the
     *  shape of a surface whose whole claim is reproducibility, to carry a term the EOD contracts
     *  artifact already publishes in full. See {@link #otcAuditRow} for the mapping. */
    record AuditRow(String kind, long inputSeq, String orderId, String tradeId, int accountId,
                    String security, String side, int quantity, BigDecimal price,
                    long timestampMillis) { }

    /** Outcome of one full-log replay, reported so a proof can assert the replay was REAL: an
     *  index that reproduces the live engine's trade population is a replay; one that does not is
     *  a bug, and either way it is visible instead of inferred. */
    record ReindexResult(int indexedTrades, long evictions, long replayedMessages,
                         long replayedAppliedSeq, long shadowTradeCounter) { }

    private final File clusterDir;
    private final String aeronDir;
    private final String epoch;
    private final int fullHistoryMax;
    private final int regulatoryMax;
    private final TradeBlotter live;

    /** Result of the most recent reindex, or null if never run — same contract as the Spring
     *  tier's {@code fullHistoryIndex()}: {@code /recon/full-history/trades} 503s until then. */
    private volatile TradeBlotter fullHistory;

    ClusterRecon(final File baseDir, final String aeronDir, final String epoch,
                 final int liveCapacity, final int fullHistoryMax, final int regulatoryMax) {
        this.clusterDir = new File(baseDir, "cluster");
        this.aeronDir = aeronDir;
        this.epoch = epoch;
        this.fullHistoryMax = fullHistoryMax;
        this.regulatoryMax = regulatoryMax;
        this.live = new TradeBlotter(liveCapacity);
    }

    // ----- live forward window ----------------------------------------------------------------

    /**
     * Live-service output sink (apply thread). Records every booked trade into the bounded forward
     * blotter that {@code /recon/trades/blotter} serves — the window
     * {@code ReconciliationService.sweep()} classifies against the projection.
     *
     * <p>Called with the reusable ring slot, so everything needed is copied out here and now. Not
     * replicated state and not in the snapshot: a restarted member rebuilds its window from the log
     * tail it replays, which is precisely the Spring tier's documented behaviour for its own live
     * blotter. Off-consensus, same as the trade bridge two lines above it in the drain loop.
     */
    void onLiveOutput(final OutputEvent out, final String ticker) {
        if (out.kind == OutputEvent.KIND_TRADE_BOOKED) {
            live.record(tradeRecord(out, ticker));
        }
    }

    List<TradeBlotter.TradeRecord> liveSince(final long sinceSeq, final int limit) {
        return live.since(sinceSeq, limit);
    }

    int liveSize() {
        return live.size();
    }

    // ----- full history (journal replay) ------------------------------------------------------

    /**
     * FR-PTC10: replay the ENTIRE committed log into a shadow engine and index every trade it
     * books. Expensive and synchronous by design — mirrors the Spring tier exactly, and like it is
     * never scheduled, always explicitly triggered. Synchronized so two admin calls cannot each
     * build a shadow engine on a consensus member at the same time.
     */
    synchronized ReindexResult reindexFullHistory() {
        final TradeBlotter index = new TradeBlotter(fullHistoryMax);
        final long[] counters = new long[2]; // replayed messages, applied seq
        final long shadowTrades = replay((out, ticker) -> {
            if (out.kind == OutputEvent.KIND_TRADE_BOOKED) {
                index.record(tradeRecord(out, ticker));
            }
            // No contract sink: this index feeds the ORPHAN sweep, which classifies trade ids
            // against the SQL projection. A swap books no trade and has no projection row.
        }, null, counters);
        if (index.evictionCount() > 0) {
            // Refuse rather than serve a truncated index: every trade it dropped would come back
            // as a FALSE ORPHAN_IN_PROJECTION, which is a louder lie than an error.
            throw new IllegalStateException("full-history index exceeded RECON_FULL_HISTORY_MAX ("
                + fullHistoryMax + "); a truncated index would report false orphans");
        }
        this.fullHistory = index;
        return new ReindexResult(index.size(), index.evictionCount(), counters[0], counters[1],
            shadowTrades);
    }

    /** Null until a reindex has run — the caller answers 503, same as the Spring tier. */
    List<TradeBlotter.TradeRecord> fullHistorySince(final long sinceSeq, final int limit) {
        final TradeBlotter index = fullHistory;
        return index == null ? null : index.since(sinceSeq, limit);
    }

    /**
     * FR-PTC20/21: journal-sourced audit export over an input-sequence range. Reproducible
     * byte-for-byte because it is a pure replay of a CLOSED prefix of the log: the same range in
     * always yields the same records out, however far the live log has moved on since.
     *
     * <p>Two sources, one range and one ordering. Order events arrive from the shadow's output
     * ring; OTC bookings arrive from the shadow's contract store, which produces no output event
     * (see the class javadoc). Both fire inside the same {@code onSessionMessage} that applied the
     * message, so the rows come out in committed-log order without a sort. A contract's id IS the
     * consensus sequence it was booked at, so {@code fromSeq}/{@code toSeq} selects both kinds by
     * the same number and needs no second parameter.
     */
    synchronized List<AuditRow> regulatoryReport(final long fromSeq, final long toSeq) {
        final List<AuditRow> rows = new ArrayList<>();
        replay((out, ticker) -> {
            if (out.inputSeq < fromSeq || (toSeq > 0 && out.inputSeq > toSeq)) {
                return;
            }
            if (!isReportableKind(out.kind)) {
                return;
            }
            refuseIfFull(rows);
            rows.add(auditRow(out, ticker));
        }, (contract, millis) -> {
            final long seq = contract[0];
            if (seq < fromSeq || (toSeq > 0 && seq > toSeq)) {
                return;
            }
            refuseIfFull(rows);
            rows.add(otcAuditRow(contract, millis));
        }, new long[2]);
        return rows;
    }

    /**
     * The report's capacity bound, checked before each append.
     *
     * <p>REFUSE, never truncate — the same posture as the full-history index, and the reason OTC
     * rows sharing this budget is safe: they push a wide report closer to its ceiling, they cannot
     * silently evict orders to make room. The ceiling itself was sized for order events alone
     * (200k, for a 512Mi heap that is also running a consensus member) and has not been resized
     * for this; at the observed rate of a handful of bookings a day that is not close, but the
     * number means "records", not "order records".
     */
    private void refuseIfFull(final List<AuditRow> rows) {
        if (rows.size() >= regulatoryMax) {
            throw new IllegalStateException("regulatory report exceeded REGULATORY_MAX_RECORDS ("
                + regulatoryMax + "); narrow fromSeq/toSeq");
        }
    }

    // ----- the replay itself ------------------------------------------------------------------

    /** Receives each drained output of the shadow apply, with its ticker already resolved. */
    private interface ReplaySink {
        void accept(OutputEvent out, String ticker);
    }

    /**
     * Receives each OTC contract the shadow booked, with the cluster time of the applying message
     * (YU17). Package-private only so {@code ClusterReconOtcTest} can drive the same seam the
     * replay drives without standing up an archive.
     */
    interface ContractSink {
        void accept(long[] contract, long timestampMillis);
    }

    /**
     * Replay every committed message in the cluster log through a shadow
     * {@link MatchingEngineClusteredService}, from log position 0 to wherever the recording stands
     * when this starts. That upper bound is captured up front, which is what makes a report over a
     * fixed {@code toSeq} reproducible while the live log keeps growing underneath it.
     *
     * <p>Walks EVERY term entry, not just the last: a leadership change starts a new log recording,
     * so a cluster that has failed over holds its history across several of them.
     *
     * @return the shadow engine's final trade counter.
     */
    private long replay(final ReplaySink sink, final ContractSink contractSink,
                        final long[] counters) {
        final MatchingEngineClusteredService shadow = new MatchingEngineClusteredService();
        shadow.initEngine();
        shadow.outputSink(out -> sink.accept(out, shadow.tickerFor(out.securityId)));
        // The service converts cluster time to millis from its Cluster handle, which a shadow has
        // no business owning; pre-divide instead so the shadow's event time is identical to the
        // live member's under either clock. Same env, so the two can never disagree.
        final boolean nanos = ClusterNodeConfig.nanosClusterClock();
        final MessageHeaderDecoder header = new MessageHeaderDecoder();
        final SessionMessageHeaderDecoder session = new SessionMessageHeaderDecoder();
        final long[] applied = counters;

        final FragmentHandler handler = (buffer, offset, length, ignored) -> {
            header.wrap(buffer, offset);
            if (header.schemaId() != SessionMessageHeaderDecoder.SCHEMA_ID
                || header.templateId() != SessionMessageHeaderDecoder.TEMPLATE_ID) {
                return; // timers, session open/close, new-term, cluster actions: not our payloads
            }
            session.wrap(buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH,
                header.blockLength(), header.version());
            final long timestamp = session.timestamp();
            applyAndProject(shadow, nanos ? timestamp / 1_000_000L : timestamp,
                buffer, offset + SESSION_HEADER_LENGTH, length - SESSION_HEADER_LENGTH,
                contractSink);
            applied[0]++;
        };

        try (Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir));
             AeronArchive archive = AeronArchive.connect(new AeronArchive.Context()
                 .aeron(aeron)
                 .ownsAeronClient(false)
                 .controlRequestChannel(ARCHIVE_LOCAL_CONTROL)
                 .controlRequestStreamId(AeronArchive.Configuration.localControlStreamId())
                 .controlResponseChannel(IPC)
                 .controlResponseStreamId(CONTROL_RESPONSE_STREAM_ID));
             RecordingLog recordingLog = new RecordingLog(clusterDir, false)) {

            final List<RecordingLog.Entry> terms = new ArrayList<>();
            for (final RecordingLog.Entry entry : recordingLog.entries()) {
                if (entry.type == RecordingLog.ENTRY_TYPE_TERM && entry.isValid) {
                    terms.add(entry);
                }
            }
            terms.sort(Comparator.comparingLong(e -> e.termBaseLogPosition));
            for (int i = 0; i < terms.size(); i++) {
                final RecordingLog.Entry term = terms.get(i);
                final long[] bounds = recordingBounds(archive, term.recordingId);
                final long from = Math.max(term.termBaseLogPosition, bounds[0]);
                final long to = i + 1 < terms.size()
                    ? Math.min(terms.get(i + 1).termBaseLogPosition, bounds[1]) : bounds[1];
                if (to > from) {
                    replayRange(aeron, archive, term.recordingId, from, to - from, handler);
                }
            }
        }
        applied[1] = shadow.appliedSeq();
        return shadow.engine().tradeCounter();
    }

    /**
     * One shadow apply, plus the committed effects that are NOT output events (YU17).
     *
     * <p>A swap booking is applied and answered with a direct egress ack, so the only evidence it
     * happened is that the shadow's contract store grew. Detecting that here — around the same
     * {@code onSessionMessage} the replay already makes, with the same cluster timestamp in hand —
     * is what gives an OTC row a real booking time without adding a column to the contract tuple,
     * which would have been a snapshot-shape change for a value the log already carries.
     *
     * <p>Package-private, and the seam the unit test drives: the replay's only other job is
     * unwrapping the cluster session header, which needs an archive and is proven on the rig.
     */
    static void applyAndProject(final MatchingEngineClusteredService shadow, final long millis,
                                final DirectBuffer buffer, final int offset, final int length,
                                final ContractSink contractSink) {
        final int before = contractSink == null ? 0 : shadow.contractCount();
        shadow.onSessionMessage(null, millis, buffer, offset, length, null);
        if (contractSink == null) {
            return;
        }
        // A loop, not an if: one apply books at most one contract today, and a projection that
        // silently drops the second one if that ever changes is the shape of this whole defect.
        for (int i = before; i < shadow.contractCount(); i++) {
            contractSink.accept(shadow.contractTuples().get(i), millis);
        }
    }

    /** {@code [startPosition, endPosition]} of a recording; the live one has no stop position. */
    private static long[] recordingBounds(final AeronArchive archive, final long recordingId) {
        final long[] bounds = { 0L, AeronArchive.NULL_POSITION };
        archive.listRecording(recordingId, (controlSessionId, correlationId, id, startTimestamp,
                stopTimestamp, startPosition, stopPosition, initialTermId, segmentFileLength,
                termBufferLength, mtuLength, sessionId, streamId, strippedChannel, originalChannel,
                sourceIdentity) -> {
            bounds[0] = startPosition;
            bounds[1] = stopPosition;
        });
        if (bounds[1] == AeronArchive.NULL_POSITION) {
            bounds[1] = archive.getRecordingPosition(recordingId);
        }
        if (bounds[1] == AeronArchive.NULL_POSITION) {
            bounds[1] = bounds[0]; // neither stopped nor actively recording: nothing to replay
        }
        return bounds;
    }

    private static void replayRange(final Aeron aeron, final AeronArchive archive,
                                    final long recordingId, final long position, final long length,
                                    final FragmentHandler handler) {
        final YieldingIdleStrategy idle = new YieldingIdleStrategy();
        final long deadline = System.currentTimeMillis() + REPLAY_DEADLINE_MS;
        Subscription subscription = null;
        long replaySessionId = Aeron.NULL_VALUE;
        try {
            subscription = aeron.addSubscription(IPC, REPLAY_STREAM_ID);
            replaySessionId = archive.startReplay(recordingId, position, length, IPC, REPLAY_STREAM_ID);
            final int imageSessionId = (int) replaySessionId;
            Image image = null;
            while (image == null) {
                image = subscription.imageBySessionId(imageSessionId);
                if (image == null) {
                    checkDeadline(deadline, "replay image never arrived for recording " + recordingId);
                    idle.idle();
                }
            }
            final FragmentAssembler assembler = new FragmentAssembler(handler);
            final long target = position + length;
            while (image.position() < target) {
                if (image.poll(assembler, 64) == 0) {
                    if (image.isClosed() || image.isEndOfStream()) {
                        break;
                    }
                    checkDeadline(deadline, "replay of recording " + recordingId + " stalled at "
                        + image.position() + " of " + target);
                    idle.idle();
                } else {
                    idle.reset();
                }
            }
        } finally {
            if (replaySessionId != Aeron.NULL_VALUE) {
                try {
                    archive.stopReplay(replaySessionId);
                } catch (final RuntimeException ignore) {
                    // already finished on its own
                }
            }
            if (subscription != null) {
                subscription.close();
            }
        }
    }

    private static void checkDeadline(final long deadline, final String what) {
        if (System.currentTimeMillis() > deadline) {
            throw new IllegalStateException(what);
        }
    }

    // ----- rendering --------------------------------------------------------------------------

    /**
     * The tier's own trade id. {@code TradeNatsPublisher} publishes {@code <tradeSeq>-B|S} and
     * trade-processor keys its row on exactly that, so the index MUST mint the same string: the
     * Spring tier's {@code trd-09b-<seq>} scheme here would make every projection row an orphan.
     */
    private TradeBlotter.TradeRecord tradeRecord(final OutputEvent out, final String ticker) {
        final boolean buy = out.side == 0; // InputEvent.SIDE_BUY
        return new TradeBlotter.TradeRecord(
            out.tradeSeq + (buy ? "-B" : "-S"),
            out.tradeSeq,
            out.accountId,
            ticker,
            OrderSide.values()[out.side].name(),
            out.tradeQty,
            Px.toDecimalOrZero(out.tradePx),
            out.updatedAtMillis);
    }

    private AuditRow auditRow(final OutputEvent out, final String ticker) {
        final boolean trade = out.kind == OutputEvent.KIND_TRADE_BOOKED;
        final boolean buy = out.side == 0;
        return new AuditRow(
            kindName(out.kind),
            out.inputSeq,
            // Epoch-qualified, matching OrderNatsPublisher — an audit trail whose order ids do not
            // resolve in the read model is not an audit trail.
            epoch + "-" + out.orderRef,
            trade ? out.tradeSeq + (buy ? "-B" : "-S") : null,
            out.accountId,
            ticker,
            OrderSide.values()[out.side].name(),
            trade ? out.tradeQty : out.quantity,
            Px.toDecimalOrZero(trade ? out.tradePx : out.limitPx),
            out.updatedAtMillis);
    }

    /**
     * One booked OTC contract as an audit row (YU17). Column order is
     * {@code MatchingEngineClusteredService.CONTRACT_TUPLE_LENGTH}'s.
     *
     * <p>The identifier, the direction and the rate render VERBATIM as {@code SwapContractCsv}
     * renders them — {@code SW-<seq>}/{@code SWPT-<seq>}, {@code PAY_FIXED}/{@code RECEIVE_FIXED},
     * six-decimal rate — because the point of this row is that the EOD contracts artifact stops
     * being the only place a booking is visible, and two audit surfaces that describe the same
     * contract in two vocabularies are two surfaces to reconcile rather than one to read.
     *
     * <p>{@code quantity} is the notional and {@code price} is the fixed rate (the STRIKE for a
     * swaption). Both are per contract and neither is netted: the offsetting pair that a position
     * grain would collapse to a single zero row is two rows here, each with its own rate.
     *
     * <p>Dates and the option wrapper are not carried — see {@link AuditRow}. The convention is
     * NAMED where this build knows it and named opaquely where it does not, which is a deliberate
     * split from {@code SwapContractCsv}: that artifact aborts on an unknown index because
     * publishing a contract under the wrong day count is worse than publishing nothing, and this
     * row does not publish a day count at all. Aborting would blank an entire regulatory report
     * over one contract booked by a later build.
     */
    static AuditRow otcAuditRow(final long[] contract, final long timestampMillis) {
        final boolean swaption = contract[8] == MatchingEngineClusteredService.PRODUCT_SWAPTION;
        final int conventionIndex = (int) contract[5];
        return new AuditRow(
            swaption ? "SWAPTION_BOOKED" : "SWAP_BOOKED",
            contract[0],
            (swaption ? "SWPT-" : "SW-") + contract[0],
            null,
            (int) contract[1],
            SwapConventions.knows(conventionIndex)
                ? SwapConventions.at(conventionIndex).name()
                : "CONVENTION_" + conventionIndex,
            contract[2] != 0L ? "PAY_FIXED" : "RECEIVE_FIXED",
            (int) contract[3],
            BigDecimal.valueOf(contract[4], SwapContractCsv.RATE_SCALE),
            timestampMillis);
    }

    private static boolean isReportableKind(final byte kind) {
        return kind == OutputEvent.KIND_ORDER_ACCEPTED
            || kind == OutputEvent.KIND_ORDER_REJECTED
            || kind == OutputEvent.KIND_ORDER_PARTIALLY_FILLED
            || kind == OutputEvent.KIND_ORDER_FILLED
            || kind == OutputEvent.KIND_ORDER_CANCELED
            || kind == OutputEvent.KIND_TRADE_BOOKED;
    }

    private static String kindName(final byte kind) {
        return switch (kind) {
            case OutputEvent.KIND_ORDER_ACCEPTED -> "ORDER_ACCEPTED";
            case OutputEvent.KIND_ORDER_REJECTED -> "ORDER_REJECTED";
            case OutputEvent.KIND_ORDER_PARTIALLY_FILLED -> "ORDER_PARTIALLY_FILLED";
            case OutputEvent.KIND_ORDER_FILLED -> "ORDER_FILLED";
            case OutputEvent.KIND_ORDER_CANCELED -> "ORDER_CANCELED";
            case OutputEvent.KIND_TRADE_BOOKED -> "TRADE_BOOKED";
            default -> "UNKNOWN_" + kind;
        };
    }
}
