export interface Symbol {
    name: string;
    sector: string;
    symbol: string;
}

// YU16 (FR-CDM27, TD-CDM01): the model keeps its historic name and shape; the CDM attributes
// ride along as optional fields mapped from GET /instruments (ticker = instrumentKey,
// companyName = displayName), so every existing consumer keeps compiling unchanged.
export interface DebtEconomicsView {
    couponRatePercent?: number;
    maturityDate?: string;
}

export interface Stock {
    ticker: string;
    companyName: string;
    assetClass?: 'Stock' | 'ETF' | 'US_TREASURY';
    securityType?: string;
    shortDisplayName?: string;
    matured?: boolean;
    debtEconomics?: DebtEconomicsView;
}

export function isTreasury(stock: Stock | undefined | null): boolean {
    return stock?.assetClass === 'US_TREASURY';
}
