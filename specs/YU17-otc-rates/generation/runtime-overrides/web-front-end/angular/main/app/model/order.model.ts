export type OrderStatus = 'NEW' | 'PARTIALLY_FILLED' | 'FILLED' | 'CANCELED' | 'REJECTED';
export type OrderSide = 'Buy' | 'Sell';

export interface OrderRecord {
    orderId: string;
    accountId: number;
    security: string;
    side: OrderSide;
    quantity: number;
    remainingQuantity: number;
    limitPrice: number;
    status: OrderStatus;
    createdAt: string;
    updatedAt: string;
    // Populated only when the risk gateway rejected this order (FR-IMRG44), e.g. "PRICE_COLLAR".
    riskReason?: string;
    // YU17: carried when this session created the order — it is what the order's trace id is
    // derived from. Absent for orders seen only on the feed (the bridge does not publish it).
    clientOrderId?: string;
}

export interface OrderCreateRequest {
    accountId: number;
    security: string;
    side: OrderSide;
    quantity: number;
    limitPrice: number;
    // Optional idempotency key (FR-IMRG14) -- if provided, retrying the same key returns the
    // original decision instead of double-submitting. YU17: it is ALSO the seed of the order's
    // deterministic trace id (see service/order-trace.ts), so the ticket now always sends one.
    clientOrderId?: string;
}

// ---- YU17: execution algo parents (YU08 engine) ---------------------------------------------

export type AlgoType = 'TWAP' | 'VWAP';

/** What the ticket asks the algo engine for. Note: no limitPrice — the engine prices each child
 *  off the live market at submission time, which is the whole point of handing it a parent. */
export interface AlgoCreateRequest {
    accountId: number;
    security: string;
    // The engine's enum is Buy/Sell, NOT BUY/SELL — sending the upper-cased form is a 400.
    side: OrderSide;
    quantity: number;
    algoType: AlgoType;
    durationSeconds: number;
    bucketSeconds: number;
}

export interface AlgoBucket {
    index: number;
    targetQuantity: number;
    childOrderId?: string | null;
    clientOrderId?: string | null;
    limitPrice?: number | null;
    submittedAt?: string | null;
    remainingQuantity?: number | null;
    filled?: boolean;
    state?: string;
}

export interface AlgoParentOrder {
    parentOrderId: string;
    accountId: number;
    security: string;
    side: OrderSide;
    quantity: number;
    algoType: AlgoType;
    durationSeconds: number;
    bucketSeconds: number;
    status: string;
    createdAt: string;
    buckets: AlgoBucket[];
}
