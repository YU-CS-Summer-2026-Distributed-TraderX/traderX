export interface Trade {
    accountid: number;
    accountId?: number;
    created: Date;
    id: string;
    quantity: number;
    price?: number;
    security: string;
    side: Side;
    state: State;
    updated: Date;
    // YU16 (FR-CDM23/27): present only on Rejected trades; surfaced verbatim in the blotter.
    rejectionReason?: string;
    sourceOrderId?: string;
}

export enum Side {
    Sell = 'Sell',
    Buy = 'Buy'
}

export enum State {
    New = 'New',
    Processing = 'Processing',
    Pending = 'Pending',
    Settled = 'Settled',
    Rejected = 'Rejected'
}

export interface Position {
    accountid: number;
    accountId?: number;
    quantity: number;
    security: string;
    averageCostBasis?: number;
    openPrice?: number;
    marketPrice?: number;
    marketValue?: number;
    costBasisValue?: number;
    pnl?: number;
    updated: Date;
}

export interface TradeTicket {
    side: 'Sell' | 'Buy';
    quantity: number;
    security: string;
    accountId: number;
}

export interface PriceTick {
    ticker: string;
    price: number;
    openPrice: number;
    closePrice: number;
    asOf: string;
    source: string;
    // YU16 (FR-CDM19): additive Treasury payload fields. price/cleanPrice are a FRACTION of
    // par (ADR-057); the UI multiplies by 100 for display and never converts back.
    assetClass?: string;
    cleanPrice?: number;
    priceSemantics?: string;
    approximateYtmPercent?: number | null;
    maturityDate?: string;
    matured?: boolean;
}

export interface PortfolioSummary {
    totalMarketValue: number;
    totalCostBasis: number;
    totalPnl: number;
}
