/**
 * YU17: listed-option (OCC) symbols.
 *
 * <p><b>Why the ticket needs this at all.</b> Reference data carries equities, ETFs, treasuries
 * and corporates — 541 instruments, none of them an option contract — so an option can never be
 * offered by the security typeahead, which is driven entirely by that catalog. The rest of the
 * system does support them: the price publisher marks them (Black-Scholes off the underlying),
 * the gateway books them, and the EOD quality gate applies the wider option band to them. The
 * only missing piece was a way to NAME one in the ticket, which is what free-form OCC entry adds.
 *
 * <p>An OCC symbol is self-describing: root + YYMMDD + C/P + strike x 1000, zero-padded to 8.
 * Underlying, expiry, right and strike are therefore DERIVED here and shown read-only, never
 * entered as separate fields — one source of truth for the contract's terms.
 */
export interface OccTerms {
    underlying: string;
    expiry: string;      // ISO yyyy-MM-dd
    right: 'Call' | 'Put';
    strike: number;
}

const OCC_PATTERN = /^([A-Z]{1,6})(\d{6})([CP])(\d{8})$/;

export function parseOcc(symbol: string | undefined | null): OccTerms | null {
    const normalized = String(symbol ?? '').trim().toUpperCase();
    const match = OCC_PATTERN.exec(normalized);
    if (!match) {
        return null;
    }
    const [, root, yymmdd, right, strikeRaw] = match;
    return {
        underlying: root,
        expiry: `20${yymmdd.slice(0, 2)}-${yymmdd.slice(2, 4)}-${yymmdd.slice(4, 6)}`,
        right: right === 'C' ? 'Call' : 'Put',
        strike: Number(strikeRaw) / 1000
    };
}

export function isOccSymbol(symbol: string | undefined | null): boolean {
    return parseOcc(symbol) != null;
}

/** Options are quoted per share but trade in contracts of 100 — the system's own convention,
 *  visible in the risk-extract cut files as contractMultiplier=100 for OCC symbols. */
export const OPTION_CONTRACT_MULTIPLIER = 100;
