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
}

export interface OrderCreateRequest {
    accountId: number;
    security: string;
    side: OrderSide;
    quantity: number;
    limitPrice: number;
    // Optional idempotency key (FR-IMRG14) -- if provided, retrying the same key returns the
    // original decision instead of double-submitting.
    clientOrderId?: string;
}
