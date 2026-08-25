package finos.traderx.ordermatcher.cluster;

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code yu17-grid-restore} (format-8 design §5 row 5, §2.4): snapshot geometry across the
 * 7 → 8 mint, at the unit seams ({@code writeSnapshot} / {@code onSnapshotRecord} — no cluster).
 *
 * <p><b>The red half, runnable against the CURRENT sources</b>: format 7 restores a two-column
 * {@code T_BOOK} ({securityId, baseLevel}) and RE-DERIVES the grid at restore — the tick is
 * whatever {@code bookFor} computes on the restoring build, not what the writing build used.
 * That acceptance is exactly what format 8 must refuse: once the tick is stored in T_BOOK and
 * {@code MIN_READABLE_SNAPSHOT_FORMAT} is 8, a format-7 snapshot has no tick column to trust and
 * must be refused loudly at the header (design §2.4: the §4 unit-misreading hazard class dies by
 * storage, so a tickless anchor may never be silently reinterpreted).
 *
 * <p>The two arms are assumption-gated on {@code SNAPSHOT_FORMAT} so exactly one runs per build:
 * the acceptance arm on today's format-7 sources (it goes red — is skipped — the moment the mint
 * lands), the refusal arm on the minted format-8 sources (compiles today because it needs only the
 * header seam; it activates when the constant moves). The header literal is {@code 7}, never the
 * constant — a refusal test that reads the constant it exists to pin moves with the constant and
 * can never fail (the lesson recorded in {@link ClusterSnapshotFormatCompatTest}). The refusal arm
 * pins {@code MIN_READABLE_SNAPSHOT_FORMAT}, which the mint must raise WITH {@code SNAPSHOT_FORMAT}
 * — raising only the writer's format leaves this arm failing, which is the tripwire working.
 *
 * <p><b>Owed by the MINT CHIP, not writable against these sources</b> (they need the three-column
 * {@code T_BOOK}, {@code bootstrapBook(securityId, baseLevel, tickPx)} and {@code retick} — none
 * of which compile today), recorded here so the obligation is versioned with its red half:
 * <ul>
 *   <li>round-trip: a tick-10 occupied FNMA book through {@code writeSnapshot} →
 *       {@code onSnapshotRecord}, byte-identical digest, restored on the STORED grid with the
 *       derivation never consulted;</li>
 *   <li>fail-closed tick validation: {@code tickPx <= 0}, {@code tickPx > 1000} with no category
 *       override, and {@code 10_000 % tickPx != 0} each refused loudly;</li>
 *   <li>the scope-§4 demonstration case: an old-derivation option book under the new build is
 *       refused, never silently misread.</li>
 * </ul>
 */
class GridRestoreFormatTest {
    private static final int HEADER_BYTES = 52;
    private static final int BOOK_LEVELS = 1 << 17;
    private static final long GLOBAL_TICK_PX = 1_000L;

    private MatchingEngineClusteredService newRestoreTarget() {
        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        service.initEngine();
        return service;
    }

    /** A format-{@code format} header carrying real book geometry (adoptBookGeometry reads it). */
    private UnsafeBuffer header(final int format) {
        final UnsafeBuffer header = new UnsafeBuffer(new byte[HEADER_BYTES]);
        header.putInt(0, MatchingEngineClusteredService.T_HEADER);
        header.putInt(4, format);
        header.putInt(40, BOOK_LEVELS);
        header.putLong(44, GLOBAL_TICK_PX);
        return header;
    }

    private UnsafeBuffer symbol(final int id, final String ticker) {
        final byte[] ascii = ticker.getBytes(StandardCharsets.US_ASCII);
        final UnsafeBuffer record = new UnsafeBuffer(new byte[12 + ascii.length]);
        record.putInt(0, MatchingEngineClusteredService.T_SYMBOL);
        record.putInt(4, id);
        record.putInt(8, ascii.length);
        record.putBytes(12, ascii);
        return record;
    }

    /** The format-7 two-column T_BOOK: {securityId, baseLevel}, longs at offsets 4 and 12. */
    private UnsafeBuffer bookTwoColumn(final int securityId, final long baseLevel) {
        final UnsafeBuffer record = new UnsafeBuffer(new byte[20]);
        record.putInt(0, MatchingEngineClusteredService.T_BOOK);
        record.putLong(4, securityId);
        record.putLong(12, baseLevel);
        return record;
    }

    @Test
    void formatSevenTicklessBookRestoresToday_theAcceptanceFormatEightMustRefuse() {
        // RED HALF (current build). A tickless T_BOOK restores and the grid is re-derived by the
        // restoring build — measured here rather than argued. Post-mint this arm is skipped and
        // its refusal twin below activates; a build where BOTH run (or neither) is misconfigured
        // and the suite-scope XML check will show one of them missing.
        assumeTrue(MatchingEngineClusteredService.SNAPSHOT_FORMAT == 7,
            "current-sources arm: the mint has landed; the refusal arm covers format 7 now");

        final MatchingEngineClusteredService target = newRestoreTarget();
        target.onSnapshotRecord(header(7), 0);
        target.onSnapshotRecord(symbol(0, "FNMA"), 0);
        // An anchor a $1-priced book could never legitimately hold on a tick-10 grid: on format 7
        // it restores anyway, because nothing in the record says which grid authored it.
        final long baseLevel = 134_464L;  // 200.000000 dollars / tick 1000 Px - levels/2 = 200_000 - 65_536
        target.onSnapshotRecord(bookTwoColumn(0, baseLevel), 0);

        assertEquals(1, target.engine().bookBaseTuples().size(),
            "the tickless book must have been accepted and anchored");
        assertEquals(baseLevel, target.engine().bookBaseTuples().get(0)[1],
            "restore adopted the anchor with no tick to interpret it by — the accepted hazard");
    }

    @Test
    void formatSevenIsRefusedAtTheHeaderOnceTheTickIsStored() {
        // GREEN HALF (activates on the minted sources). The header literal stays 7; the assertion
        // is against MIN_READABLE, which the mint must raise to 8 alongside SNAPSHOT_FORMAT —
        // design §2.4: old snapshots lack the tick column, so the raise is doubly required and
        // restore must never see a two-column T_BOOK.
        assumeTrue(MatchingEngineClusteredService.SNAPSHOT_FORMAT >= 8,
            "refusal arm: waiting for the format-8 mint");

        assertTrue(MatchingEngineClusteredService.MIN_READABLE_SNAPSHOT_FORMAT >= 8,
            "format 8 stores the tick in T_BOOK; leaving MIN_READABLE below 8 lets a tickless"
                + " snapshot restore under a build that would silently re-derive its geometry");
        final MatchingEngineClusteredService target = newRestoreTarget();
        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> target.onSnapshotRecord(header(7), 0),
            "a format-7 (tickless T_BOOK) snapshot must be refused loudly at the header");
        assertTrue(thrown.getMessage().contains("older"),
            "the refusal must name the direction (too old), got: " + thrown.getMessage());
    }
}
