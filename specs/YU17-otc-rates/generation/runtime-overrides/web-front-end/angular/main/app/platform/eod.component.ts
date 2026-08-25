import { Component, OnInit } from '@angular/core';
import { PlatformService, EodChain, EodStage, Reading } from './platform.service';

/**
 * The end-of-day session chain, and the provenance of the cuts it writes.
 *
 * Four stages in one pipeline, not four features: prices close, P&L is struck against them, the
 * risk extract is cut, and the cut is published to the archive. A stage can only be read in the
 * light of the one before it — an extract of zero rows is correct after a session with no
 * positions and a fault after one with them.
 *
 * <p>The business date is the TRADE-PROCESSOR's, not the browser's. Those containers run UTC, so
 * late in the US evening the rig is already on tomorrow's session and a date picked from this
 * machine's clock would query the wrong one.
 */
@Component({
    selector: 'app-eod',
    standalone: false,
    templateUrl: './eod.component.html'
})
export class EodComponent implements OnInit {
    date = new Date().toISOString().slice(0, 10);
    chain: Reading<EodChain> | null = null;
    archived: Reading<string[]> | null = null;
    loading = false;

    /** States the chain reports that this view knows how to colour. Anything else renders neutral. */
    private static readonly GOOD = ['ok', 'published', 'closed', 'done', 'complete'];
    private static readonly BAD = ['error', 'failed', 'missing', 'absent'];
    private static readonly WARN = ['pending', 'partial', 'stale', 'empty'];

    constructor(private platform: PlatformService) {}

    ngOnInit(): void { this.load(); }

    load(): void {
        this.loading = true;
        this.platform.getEodChain(this.date).subscribe(r => {
            this.chain = r;
            this.loading = false;
            // Adopt the rig's own business date rather than arguing with it: if the chain reports a
            // different session than the one asked for, the rig is right and this machine's clock
            // is not. Re-query once, and only when it actually differs, so this cannot loop.
            if (r.ok && r.value.businessDate && r.value.businessDate !== this.date) {
                this.date = r.value.businessDate;
                this.platform.getEodChain(this.date).subscribe(again => (this.chain = again));
            }
        });
        this.platform.getArchivedCuts().subscribe(r => (this.archived = r));
    }

    get stages(): { name: string; stage: EodStage }[] {
        if (!this.chain || !this.chain.ok) { return []; }
        const c = this.chain.value;
        return [
            { name: 'Prices closed', stage: c.prices },
            { name: 'P&L struck', stage: c.pnl },
            { name: 'Risk extract cut', stage: c.extract },
            { name: 'Published to archive', stage: c.published }
        ];
    }

    get chainError(): string | null { return this.chain && !this.chain.ok ? this.chain.error : null; }
    /** Session cuts, newest first — proof uploads are listed apart rather than sorted among them. */
    get cuts(): string[] {
        return this.archived && this.archived.ok ? PlatformService.sessionCuts(this.archived.value) : [];
    }
    get proofs(): string[] {
        return this.archived && this.archived.ok ? PlatformService.proofObjects(this.archived.value) : [];
    }
    get cutsError(): string | null { return this.archived && !this.archived.ok ? this.archived.error : null; }
    get businessDate(): string | null {
        return this.chain && this.chain.ok ? (this.chain.value.businessDate || null) : null;
    }

    stageClass(s: EodStage): string {
        const v = (s && s.state ? s.state : '').toLowerCase();
        if (EodComponent.GOOD.indexOf(v) >= 0) { return 'bg-success'; }
        if (EodComponent.BAD.indexOf(v) >= 0) { return 'bg-danger'; }
        if (EodComponent.WARN.indexOf(v) >= 0) { return 'bg-warning text-dark'; }
        return 'bg-secondary';
    }
}
