package finos.traderx.ordermatcher.cluster;

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
 * <p><b>Delivered by the MINT CHIP</b> (the arms after the refusal twin): a tick-10 occupied book
 * round-tripped through {@code writeSnapshot} -> {@code onSnapshotRecord} and restored on the
 * STORED grid with the derivation never consulted; the three fail-closed tick validations; and the
 * scope-section-4 demonstration case, which is the finding that made the MIN_READABLE raise
 * mandatory rather than merely tidy.
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

    // ----- the mint's own arms ----------------------------------------------------------------

    /** The format-8 three-column T_BOOK: {securityId, baseLevel, tickPx}. */
    private UnsafeBuffer bookThreeColumn(final int securityId, final long baseLevel, final long tickPx) {
        final UnsafeBuffer record = new UnsafeBuffer(new byte[28]);
        record.putInt(0, MatchingEngineClusteredService.T_BOOK);
        record.putLong(4, securityId);
        record.putLong(12, baseLevel);
        record.putLong(20, tickPx);
        return record;
    }

    @Test
    void anOccupiedFineGridBookRestoresOnTheSTOREDTickWithTheDerivationNeverConsulted() {
        // The round trip, and the reason the tick is stored at all. FNMA marks ~$1.12, so the map
        // derives tick 10 for it -- but the point of storage is that restore does NOT have to
        // agree: the restoring member is handed a grid that its own derivation would not produce
        // (the record below carries no price at all, so tickPxForBook would answer the global
        // 1000) and must rebuild on the STORED one regardless. That is what retires the
        // unit-misreading hazard class: no future change to the derivation can reinterpret an old
        // anchor, because the unit rides beside it.
        final MatchingEngineClusteredService target = newRestoreTarget();
        target.onSnapshotRecord(header(MatchingEngineClusteredService.SNAPSHOT_FORMAT), 0);
        target.onSnapshotRecord(symbol(0, "FNMA"), 0);
        final long tickPx = 10L;
        final long baseLevel = 1_120_000L / tickPx - (BOOK_LEVELS >> 1);   // $1.12 mid-band on tick 10
        target.onSnapshotRecord(bookThreeColumn(0, baseLevel, tickPx), 0);

        assertEquals(1, target.engine().bookBaseTuples().size());
        final long[] restored = target.engine().bookBaseTuples().get(0);
        assertEquals(0L, restored[0]);
        assertEquals(baseLevel, restored[1], "the anchor survived");
        assertEquals(tickPx, restored[2],
            "and its UNIT survived with it -- the whole content of the format-8 T_BOOK change");
        assertEquals(tickPx, target.engine().bookTickPxOf(0),
            "the book was rebuilt on the stored grid, not on what this build would derive"
                + " (no price is restored here, so the derivation would have answered "
                + GLOBAL_TICK_PX + ")");
    }

    @Test
    void aBookGeometryRoundTripIsByteIdentical() {
        // Write -> restore -> write again: the second stream's T_BOOK records must equal the
        // first's. This is the assertion that would catch a tick written in one unit and restored
        // in another, which no single-direction check can see.
        final MatchingEngineClusteredService source = newRestoreTarget();
        source.onSnapshotRecord(header(MatchingEngineClusteredService.SNAPSHOT_FORMAT), 0);
        source.onSnapshotRecord(symbol(0, "FNMA"), 0);
        source.onSnapshotRecord(bookThreeColumn(0, 46_464L, 10L), 0);

        final java.util.List<byte[]> first = bookRecords(source);
        assertEquals(1, first.size(), "precondition: one book to round-trip");

        final MatchingEngineClusteredService replica = newRestoreTarget();
        replica.onSnapshotRecord(header(MatchingEngineClusteredService.SNAPSHOT_FORMAT), 0);
        replica.onSnapshotRecord(symbol(0, "FNMA"), 0);
        for (final byte[] record : first) {
            replica.onSnapshotRecord(new UnsafeBuffer(record), 0);
        }
        assertArrayEquals(first.get(0), bookRecords(replica).get(0),
            "the book record must survive a write/restore/write cycle byte for byte");
    }

    @Test
    void anImpossibleStoredTickIsRefusedLoudly() {
        // Fail closed, the same posture as bootstrapOrder's off-grid and outside-band checks (which
        // remain as the second tripwire). Each value is one this build's writer cannot produce.
        for (final long tickPx : new long[] {
                0L,          // no grid at all
                -10L,        // negative: slotFor would divide by it
                3L,          // does not divide 10 000, so a cent is off-grid
                10_000L }) { // coarser than the global cap, with no ticker category to justify it
            final MatchingEngineClusteredService target = newRestoreTarget();
            target.onSnapshotRecord(header(MatchingEngineClusteredService.SNAPSHOT_FORMAT), 0);
            target.onSnapshotRecord(symbol(0, "FNMA"), 0);
            assertThrows(IllegalStateException.class,
                () -> target.onSnapshotRecord(bookThreeColumn(0, 1_000L, tickPx), 0),
                "stored tick " + tickPx + " must be refused at restore");
        }
    }

    @Test
    void aCategoryMandatedTickAboveTheCapIsStillAccepted() {
        // ...and the cap check is not blind: a ticker whose CATEGORY mandates its grid is judged
        // against that category, not against the global cap. Nothing in the seeded universe needs
        // this today (bonds mandate tick 1, well under the cap), but a future coarse-grid category
        // must not be refused by a rule written for the map. The control below is what makes the
        // arm above a real check rather than "everything unusual is refused".
        final MatchingEngineClusteredService target = newRestoreTarget();
        target.onSnapshotRecord(header(MatchingEngineClusteredService.SNAPSHOT_FORMAT), 0);
        target.onSnapshotRecord(symbol(0, "UST-20280630"), 0);
        target.onSnapshotRecord(bookThreeColumn(0, 1_000L, 1L), 0);
        assertEquals(1L, target.engine().bookTickPxOf(0),
            "a fraction-of-par ticker keeps its ADR-060 grid across restore");
    }

    @Test
    void theScopeSectionFourDemonstration_anOldDerivationOptionBookIsNeverSilentlyMisread() {
        // THE FINDING THAT MADE THE MINT MANDATORY, demonstrated rather than reasoned. Under the
        // pre-8 rule an option book's anchor was written in 1000-Px-tick units with the unit stored
        // nowhere. This build derives that same option's grid from its premium (~$0.50 -> tick 1),
        // so a tickless anchor of 134_464 would be reinterpreted as 134_464 tick-1 levels -- an
        // anchor at $0.134 instead of $200, silently, with the book restoring "successfully".
        //
        // Two independent things stop it, and BOTH are asserted because either alone could be
        // removed without the other noticing:
        //   1. the header refuses format 7 outright (MIN_READABLE 8), so the record never arrives;
        //   2. and were it fed to the format-8 reader anyway, the two-column record's missing third
        //      column reads as tick 0 and fails the fail-closed validation rather than defaulting.
        final MatchingEngineClusteredService viaHeader = newRestoreTarget();
        assertThrows(IllegalStateException.class, () -> viaHeader.onSnapshotRecord(header(7), 0),
            "a format-7 snapshot must not reach the record reader at all");

        final MatchingEngineClusteredService direct = newRestoreTarget();
        direct.onSnapshotRecord(header(MatchingEngineClusteredService.SNAPSHOT_FORMAT), 0);
        direct.onSnapshotRecord(symbol(0, "AAPL260918C00260000"), 0);
        final UnsafeBuffer tickless = new UnsafeBuffer(new byte[28]);
        tickless.putInt(0, MatchingEngineClusteredService.T_BOOK);
        tickless.putLong(4, 0L);
        tickless.putLong(12, 134_464L);   // the pre-8 anchor, in 1000-Px-tick units
        // ...and no third column: the bytes read as tick 0.
        assertThrows(IllegalStateException.class, () -> direct.onSnapshotRecord(tickless, 0),
            "a tickless anchor must fail closed, never be reinterpreted at this build's scale");
        assertEquals(0, direct.engine().bookBaseTuples().size(),
            "and no book was created from it");
    }

    /** Every T_BOOK record this service would write, as raw bytes. */
    private java.util.List<byte[]> bookRecords(final MatchingEngineClusteredService service) {
        final java.util.List<byte[]> out = new java.util.ArrayList<>();
        service.writeSnapshot((buffer, offset, length) -> {
            if (buffer.getInt(offset) != MatchingEngineClusteredService.T_BOOK) {
                return;
            }
            final byte[] copy = new byte[length];
            buffer.getBytes(offset, copy);
            out.add(copy);
        });
        return out;
    }
}
