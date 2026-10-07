package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.OutputEvent;
import java.util.Arrays;

/**
 * Owner-thread diagnostic history, never a source of completion or admission decisions.
 * One direct-mapped slot per inflight-capacity slot; collisions discard evidence, not acks.
 * No clock/retention promise: overwritten and session-reset evidence becomes unknown.
 * Lifecycle acks carry no fill ordinal (tradeSeq is zero), so repeated PARTIAL records
 * cannot be distinguished from distinct match steps and MUST NOT be called duplicates.
 */
final class GatewayAckHistory {
    private static final byte COMPLETED = 1, REAPED = 2, DRAINED = 3, UNCERTAIN = 4;
    private final long[] requestIds, appliedSequences;
    private final int[] orderRefs;
    private final byte[] kinds, reasons, states;
    private volatile long continuation, duplicate, lateReaped, lateDrained, otherSession, unknown;
    private volatile long evictions, resets;

    GatewayAckHistory(final int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("ack history capacity must be positive");
        }
        requestIds = new long[capacity];
        appliedSequences = new long[capacity];
        orderRefs = new int[capacity];
        kinds = new byte[capacity];
        reasons = new byte[capacity];
        states = new byte[capacity];
    }

    private int slot(final long id) {
        return (int) Long.remainderUnsigned(id, requestIds.length);
    }

    private int replace(final long id, final byte state) {
        final int i = slot(id);
        if (requestIds[i] != 0 && requestIds[i] != id) {
            evictions++;
        }
        requestIds[i] = id;
        states[i] = state;
        return i;
    }

    /** A synthetic/test reuse cannot inherit a previous request's completion evidence. */
    void registered(final long id) {
        final int i = slot(id);
        if (requestIds[i] == id) {
            requestIds[i] = 0;
        }
    }

    void completed(final long id, final long seq, final int ref, final byte kind, final byte reason) {
        final int i = replace(id, COMPLETED);
        appliedSequences[i] = seq;
        orderRefs[i] = ref;
        kinds[i] = kind;
        reasons[i] = reason;
    }

    void reaped(final long id) { replace(id, REAPED); }
    void drained(final long id) { replace(id, DRAINED); }
    void resetSession() { Arrays.fill(requestIds, 0); resets++; }
    void otherSession() { otherSession++; }
    void unknownSession() { unknown++; }

    /** Missing pending: classify only using retained evidence from the current session. */
    void unmatched(final long id, final long seq, final int ref, final byte kind, final byte reason) {
        final int i = slot(id);
        if (requestIds[i] != id) {
            unknown++;
            return;
        }
        if (states[i] == REAPED) {
            lateReaped++;
            return;
        }
        if (states[i] == DRAINED) {
            lateDrained++;
            return;
        }
        if (states[i] == UNCERTAIN || appliedSequences[i] != seq || seq <= 0 || orderRefs[i] != ref
                || ref <= 0 || reason != reasons[i]) {
            unknown++;
            return;
        }
        final byte previous = kinds[i];
        if (previous == kind) {
            // Identical PARTIAL bytes also describe a NEW match step: identity is unavailable.
            if (kind == OutputEvent.KIND_ORDER_PARTIALLY_FILLED) {
                unknown++;
            } else {
                duplicate++;
            }
        } else if (reason == 0 && ((previous == OutputEvent.KIND_ORDER_ACCEPTED
                    && (kind == OutputEvent.KIND_ORDER_PARTIALLY_FILLED || kind == OutputEvent.KIND_ORDER_FILLED))
                || (previous == OutputEvent.KIND_ORDER_PARTIALLY_FILLED && kind == OutputEvent.KIND_ORDER_FILLED))) {
            continuation++;
            kinds[i] = kind;
        } else {
            // An unrecognized same-tuple transition cannot license a later expected fill.
            unknown++;
            states[i] = UNCERTAIN;
        }
    }

    String metrics() {
        return "traderx_gateway_ack_unmatched_total{reason=\"continuation\"} " + continuation + "\n"
            + "traderx_gateway_ack_unmatched_total{reason=\"duplicate\"} " + duplicate + "\n"
            + "traderx_gateway_ack_unmatched_total{reason=\"late_reaped\"} " + lateReaped + "\n"
            + "traderx_gateway_ack_unmatched_total{reason=\"late_drained\"} " + lateDrained + "\n"
            + "traderx_gateway_ack_unmatched_total{reason=\"other_session\"} " + otherSession + "\n"
            + "traderx_gateway_ack_unmatched_total{reason=\"unknown\"} " + unknown + "\n"
            + "traderx_gateway_ack_history_capacity " + requestIds.length + "\n"
            + "traderx_gateway_ack_history_evictions_total " + evictions + "\n"
            + "traderx_gateway_ack_history_resets_total " + resets + "\n";
    }
}
