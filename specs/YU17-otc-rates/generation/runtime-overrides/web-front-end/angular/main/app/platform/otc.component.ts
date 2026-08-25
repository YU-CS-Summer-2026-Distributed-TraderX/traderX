import { Component, OnInit } from '@angular/core';
import { PlatformService, Reading } from './platform.service';

/**
 * Swaps and swaptions, read out of the end-of-day cut.
 *
 * <p><b>Why the cut, and not a table.</b> On this tier the OTC booking routes are write-only: there
 * is no contract table to query, and the regulatory report enumerates order and trade kinds only.
 * A booked swap is therefore invisible everywhere on this tier until the next cut, which carries it
 * at (accountId, security) grain with an instrumentType. That makes the cut the ONLY read model
 * these instruments have, so this view reads the newest archived one rather than pretending a live
 * query exists.
 *
 * <p>Consequence worth knowing before reading an empty table: swaps booked since the last cut are
 * not here, and that is not a fault. The header states which sequence and session the rows describe.
 */
@Component({
    selector: 'app-otc',
    standalone: false,
    templateUrl: './otc.component.html'
})
export class OtcComponent implements OnInit {
    cutPath: string | null = null;
    meta: { [k: string]: string } = {};
    rows: OtcRow[] = [];
    error: string | null = null;
    loading = false;

    /** Instrument types this view considers OTC. Anything else in the cut is somebody else's row. */
    private static readonly OTC = ['SWAP', 'SWAPTION'];

    constructor(private platform: PlatformService) {}

    ngOnInit(): void { this.load(); }

    load(): void {
        this.loading = true;
        this.error = null;
        this.platform.getArchivedCuts().subscribe(list => {
            if (!list.ok) { this.error = list.error; this.loading = false; return; }
            const newest = list.value.length ? list.value[0] : null;
            if (!newest) { this.error = 'no cut in the archive yet'; this.loading = false; return; }
            this.cutPath = newest;
            this.platform.getCut(newest).subscribe(cut => {
                this.loading = false;
                if (!cut.ok) { this.error = cut.error; return; }
                this.parse(cut.value.content || '');
            });
        });
    }

    /**
     * The cut is a CSV with `# key=value` provenance lines above the header. Both halves matter:
     * the rows say what is held, the header says at which consensus sequence they were true.
     */
    private parse(text: string): void {
        const lines = text.split('\n');
        const meta: { [k: string]: string } = {};
        const rows: OtcRow[] = [];
        let cols: string[] = [];
        for (const raw of lines) {
            const line = raw.trim();
            if (!line) { continue; }
            if (line.charAt(0) === '#') {
                const eq = line.indexOf('=');
                if (eq > 1) { meta[line.slice(1, eq).trim()] = line.slice(eq + 1).trim(); }
                continue;
            }
            const cells = line.split(',');
            if (!cols.length) { cols = cells; continue; }
            const row: any = {};
            for (let i = 0; i < cols.length; i++) { row[cols[i]] = cells[i]; }
            if (OtcComponent.OTC.indexOf((row.instrumentType || '').toUpperCase()) >= 0) {
                rows.push({
                    accountId: row.accountId, security: row.security,
                    instrumentType: row.instrumentType, quantity: Number(row.quantity),
                    costBasis: Number(row.costBasis), closingMark: Number(row.closingMark),
                    marketValue: Number(row.marketValue), unrealizedPnl: Number(row.unrealizedPnl),
                    counterpartyId: row.counterpartyId, nettingSetId: row.nettingSetId
                });
            }
        }
        this.meta = meta;
        this.rows = rows;
    }

    get sequence(): string { return this.meta['consensusSequence'] || '-'; }
    get sessionDate(): string { return this.meta['sessionDate'] || '-'; }
    get totalRows(): string { return this.meta['rows'] || '-'; }
}

export interface OtcRow {
    accountId: string; security: string; instrumentType: string;
    quantity: number; costBasis: number; closingMark: number;
    marketValue: number; unrealizedPnl: number;
    counterpartyId: string; nettingSetId: string;
}
