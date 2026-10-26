package finos.traderx.ordermatcher.cluster;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EOD-RISK item 2: a cut larger than one NATS message arrives whole or not at all. */
class RiskExtractCutPartsTest {

    private static byte[] cut(final long seq, final int rows) {
        final StringBuilder sb = new StringBuilder("#cut schema=3 seq=" + seq + " sessionDateEpochDay=20000"
            + " priceVersion=1 rows=" + rows + " contracts=0\n" + RiskExtractCut.HEADER + "\n");
        for (int i = 0; i < rows; i++) {
            sb.append(i + 1).append(",SYM").append(i % 97).append(",100,1000000,1,1000000\n");
        }
        sb.append("#contracts\n").append(RiskExtractCut.CONTRACTS_HEADER).append('\n');
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static String assemble(final long seq, final List<byte[]> wire) {
        final RiskExtractCutPublisher.Assembly a = new RiskExtractCutPublisher.Assembly(seq);
        String out = null;
        for (final byte[] m : wire) {
            final String r = a.accept(m);
            if (r != null) {
                assertNull(out, "completed twice");
                out = r;
            }
        }
        return out;
    }

    @Test
    void anyPartSizeAndArrivalOrderReassemblesTheExactCut() {
        final byte[] cut = cut(41, 5000);
        final String sha = RiskExtractCut.sha256(cut);
        for (final int partBytes : new int[] {cut.length * 2, cut.length, cut.length - 1, 4096, 1000, 1}) {
            if (partBytes == 1 && cut.length > 20_000) {
                continue; // one message per byte is a pathological wire, not a size we ship
            }
            final List<byte[]> wire = new ArrayList<>(RiskExtractCutPublisher.parts(41, cut, sha, partBytes));
            assertEquals(RiskExtractCutPublisher.partCount(cut.length, partBytes), wire.size());
            Collections.shuffle(wire, new Random(partBytes));
            assertEquals(new String(cut, StandardCharsets.US_ASCII), assemble(41, wire), "partBytes=" + partBytes);
        }
    }

    @Test
    void aMissingPartNeverCompletes() {
        final byte[] cut = cut(41, 3000);
        final List<byte[]> wire = new ArrayList<>(RiskExtractCutPublisher.parts(41, cut, RiskExtractCut.sha256(cut), 4096));
        assertTrue(wire.size() > 3);
        wire.remove(2);
        assertNull(assemble(41, wire), "a cut with a part missing must time out, not arrive short");
    }

    @Test
    void aCorruptedPartIsRefusedByTheHash() {
        final byte[] cut = cut(41, 3000);
        final List<byte[]> wire = RiskExtractCutPublisher.parts(41, cut, RiskExtractCut.sha256(cut), 4096);
        final byte[] last = wire.get(wire.size() - 1);
        last[last.length - 2] ^= 1;
        final IllegalStateException e = assertThrows(IllegalStateException.class, () -> assemble(41, wire));
        assertTrue(e.getMessage().contains("declared"), e.getMessage());
    }

    @Test
    void aTruncatedPartIsRefusedByTheLength() {
        final byte[] cut = cut(41, 3000);
        final List<byte[]> wire = new ArrayList<>(RiskExtractCutPublisher.parts(41, cut, RiskExtractCut.sha256(cut), 4096));
        final byte[] first = wire.get(0);
        wire.set(0, java.util.Arrays.copyOf(first, first.length - 10));
        assertThrows(IllegalStateException.class, () -> assemble(41, wire));
    }

    @Test
    void partsOfAnotherSequenceAreIgnoredAndContradictionsRefused() {
        final byte[] ours = cut(41, 2000);
        final byte[] stale = cut(40, 2500);
        final List<byte[]> wire = new ArrayList<>(RiskExtractCutPublisher.parts(40, stale, RiskExtractCut.sha256(stale), 4096));
        wire.addAll(RiskExtractCutPublisher.parts(41, ours, RiskExtractCut.sha256(ours), 4096));
        assertEquals(new String(ours, StandardCharsets.US_ASCII), assemble(41, wire));

        final List<byte[]> mixed = new ArrayList<>(RiskExtractCutPublisher.parts(41, ours, RiskExtractCut.sha256(ours), 4096));
        mixed.addAll(RiskExtractCutPublisher.parts(41, ours, RiskExtractCut.sha256(ours), 3000)); // a different split
        assertThrows(IllegalStateException.class, () -> assemble(41, mixed));
    }

    @Test
    void anIdenticalDuplicatePartIsHarmless() {
        final byte[] cut = cut(41, 2000);
        final List<byte[]> wire = new ArrayList<>(RiskExtractCutPublisher.parts(41, cut, RiskExtractCut.sha256(cut), 4096));
        wire.add(1, wire.get(0).clone());
        assertEquals(new String(cut, StandardCharsets.US_ASCII), assemble(41, wire));
    }

    @Test
    void aPreYu18SingleMessageCutIsStillAccepted() {
        final byte[] cut = cut(41, 10);
        assertEquals(new String(cut, StandardCharsets.US_ASCII), assemble(41, List.of(cut(40, 10), cut)));
    }
}
