/**
 * YU17: derive an order's W3C trace id in the browser.
 *
 * <p>KEEP IN SYNC with
 * {@code order-matcher/src/main/java/finos/traderx/ordermatcher/cluster/OrderTrace.java} and
 * {@code clientOrderKey} in {@code ClusterGatewayMain}: this reproduces that FNV-1a/mix math, and
 * a change there silently breaks trace lookup here — in a demo, not in review.
 *
 * <p>The gateway derives the trace id deterministically from the order itself (its clientOrderId,
 * or {@code mix(orderRef)} when key-less), so a client that knows the math can name any order's
 * trace without being told it. Verified byte-equal against the gateway's own ORDER-REJECT log
 * line before this shipped. Rejected orders are always head-sampled, so a refusal's trace is
 * guaranteed to exist in Tempo; accepted orders are sampled 1-in-N.
 */
const M64 = (1n << 64n) - 1n;
const TRACE_SALT = 0x5851F42D4C957F2Dn;

function mix64(input: bigint): bigint {
    let z = (input + 0x9E3779B97F4A7C15n) & M64;
    z = ((z ^ (z >> 30n)) * 0xBF58476D1CE4E5B9n) & M64;
    z = ((z ^ (z >> 27n)) * 0x94D049BB133111EBn) & M64;
    return (z ^ (z >> 31n)) & M64;
}

function fnv64(text: string): bigint {
    let hash = 0xcbf29ce484222325n;
    for (let i = 0; i < text.length; i++) {
        hash = ((hash ^ BigInt(text.charCodeAt(i))) * 0x100000001b3n) & M64;
    }
    return hash === 0n ? 1n : hash;
}

const hex16 = (value: bigint): string => value.toString(16).padStart(16, '0');
const nonZero = (value: bigint): bigint => (value === 0n ? 1n : value);

/** "1-2549" | "2549" -> 2549 (the epoch prefix is not part of the trace key). */
function orderRefOf(orderId: string | number | undefined): number | null {
    const text = String(orderId ?? '').trim();
    if (!text) {
        return null;
    }
    const tail = text.includes('-') ? text.slice(text.lastIndexOf('-') + 1) : text;
    const ref = Number(tail);
    return Number.isFinite(ref) && ref > 0 ? ref : null;
}

export function traceIdFor(clientOrderId?: string, orderId?: string | number): string | undefined {
    let key: bigint;
    if (clientOrderId && clientOrderId.trim()) {
        key = fnv64(clientOrderId.trim());
    } else {
        const ref = orderRefOf(orderId);
        if (ref == null) {
            return undefined;
        }
        key = mix64(BigInt(ref));
    }
    return hex16(nonZero(mix64(key))) + hex16(nonZero(mix64(key ^ TRACE_SALT)));
}
