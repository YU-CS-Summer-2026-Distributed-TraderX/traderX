import { Component, OnInit } from '@angular/core';
import { PlatformService } from './platform.service';

/**
 * Swaps and swaptions, read from the end-of-day cut's CONTRACTS artifact.
 *
 * <p><b>Their own file, not the position extract.</b> These instruments are carried at CONTRACT
 * grain and never as positions — a receive-fixed and a pay-fixed of equal notional would net to
 * zero as positions and destroy both rates. So each cut writes a companion
 * `seq-&lt;n&gt;-contracts.csv` beside the netted position extract, and this view reads that.
 * Filtering the position extract for an OTC instrument type, which is the obvious approach, finds
 * nothing and would look like "no contracts booked" rather than "wrong file".
 *
 * <p><b>Why the cut at all.</b> On this tier the booking routes are write-only: there is no
 * contract table to query and the regulatory report enumerates order and trade kinds only. The cut
 * is the only read model these instruments have, so anything booked since the last one is not here.
 *
 * <p>An empty table is therefore a real answer, and it is reported alongside the header's own
 * `contracts=` count so it cannot be confused with a failed read.
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

    constructor(private platform: PlatformService) {}

    ngOnInit(): void { this.load(); }

    load(): void {
        this.loading = true;
        this.error = null;
        this.rows = [];
        this.platform.getArchivedCuts().subscribe(list => {
            if (!list.ok) { this.error = list.error; this.loading = false; return; }
            const newest = OtcComponent.newestContracts(list.value);
            if (!newest) { this.error = 'no contracts artifact in the archive yet'; this.loading = false; return; }
            this.cutPath = newest;
            this.platform.getCut(newest).subscribe(cut => {
                this.loading = false;
                if (!cut.ok) { this.error = cut.error; return; }
                this.parse(cut.value.content || '');
            });
        });
    }

    /**
     * The newest SESSION contracts artifact.
     *
     * Sorting the listing lexicographically and taking the last entry picks
     * `proof/<millis>/seq-0.cut` — an upload-proof object, not a session cut, which sorts after
     * every dated prefix. Measured: that is exactly what this view showed before. So proof objects
     * are excluded by shape, and the winner is chosen on (date, version) numerically, because v10
     * must beat v9 and string order would not.
     */
    static newestContracts(files: string[]): string | null {
        const parsed = files
            .filter(f => f.indexOf('/proof/') < 0 && /-contracts\.csv$/.test(f))
            .map(f => {
                const m = /\/(\d{4}-\d{2}-\d{2})\/v(\d+)\//.exec(f);
                return m ? { f: f, date: m[1], version: Number(m[2]) } : null;
            })
            .filter(x => !!x) as { f: string; date: string; version: number }[];
        if (!parsed.length) { return null; }
        parsed.sort((a, b) => a.date === b.date ? a.version - b.version : a.date.localeCompare(b.date));
        return parsed[parsed.length - 1].f;
    }

    /** `# key=value` provenance lines above a CSV header. Both halves matter. */
    private parse(text: string): void {
        const meta: { [k: string]: string } = {};
        const rows: OtcRow[] = [];
        let cols: string[] = [];
        for (const raw of text.split('\n')) {
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
            rows.push(row as OtcRow);
        }
        this.meta = meta;
        this.rows = rows;
    }

    get sequence(): string { return this.meta['consensusSequence'] || '-'; }
    get sessionDate(): string { return this.meta['sessionDate'] || '-'; }
    get declaredCount(): string { return this.meta['contracts'] || '-'; }

    /** The header's count against the rows actually parsed — they must agree. */
    get mismatch(): boolean {
        const declared = Number(this.meta['contracts']);
        return !isNaN(declared) && declared !== this.rows.length;
    }
}

export interface OtcRow {
    contractId: string; accountId: string; payReceive: string; notional: string;
    fixedRate: string; floatIndex: string; effectiveDate: string; maturityDate: string;
    paymentFrequency: string; dayCount: string; currency: string;
    counterpartyId: string; nettingSetId: string; productType: string; expiry: string;
}
