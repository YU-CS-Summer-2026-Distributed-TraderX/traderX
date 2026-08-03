import { createHash } from 'crypto';
import { Stock } from './stock.model';

/**
 * Watermarked snapshot response for `GET /stocks/control-snapshot` (ADR-019 step 2, ADR-021).
 * Additive/new endpoint — the existing `GET /stocks` array shape is untouched.
 */
export interface ControlSnapshot {
  schemaVersion: number;
  sourceEpoch: number;
  watermark: number;
  count: number;
  checksum: string;
  records: Stock[];
}

const SCHEMA_VERSION = 1;

/** SHA-256 over the canonical (ticker-sorted) record set, so a consumer can verify it independently. */
export function buildControlSnapshot(
  sourceEpoch: number,
  watermark: number,
  stocks: Stock[],
): ControlSnapshot {
  const sorted = [...stocks].sort((a, b) => a.ticker.localeCompare(b.ticker));
  let canonical = '';
  for (const stock of sorted) {
    canonical += `${stock.ticker}:${stock.companyName};`;
  }
  const checksum = `sha256:${createHash('sha256').update(canonical, 'utf8').digest('hex')}`;
  return {
    schemaVersion: SCHEMA_VERSION,
    sourceEpoch,
    watermark,
    count: sorted.length,
    checksum,
    records: sorted,
  };
}
