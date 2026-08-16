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
    assetClass?: 'Stock' | 'ETF' | 'US_TREASURY' | 'CORPORATE_BOND';
    securityType?: string;
    shortDisplayName?: string;
    matured?: boolean;
    debtEconomics?: DebtEconomicsView;
}

export function isTreasury(stock: Stock | undefined | null): boolean {
    return stock?.assetClass === 'US_TREASURY';
}

/**
 * Any bond — Treasury or corporate. The ticket's FACE-AMOUNT semantics key off this: quantity is
 * USD face and the price is a fraction of par for every debt instrument, so the labels, the value
 * estimate and the minimum/increment rule all apply to both. isTreasury() stays for the places
 * that genuinely mean "government", which is not the same question.
 */
export function isBond(stock: Stock | undefined | null): boolean {
    return stock?.securityType === 'Debt'
        || stock?.assetClass === 'US_TREASURY' || stock?.assetClass === 'CORPORATE_BOND';
}
