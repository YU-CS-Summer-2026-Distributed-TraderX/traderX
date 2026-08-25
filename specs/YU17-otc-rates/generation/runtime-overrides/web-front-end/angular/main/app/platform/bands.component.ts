import { Component, OnInit } from '@angular/core';
import { PlatformService, BandCheck, RegulatoryEvent, Reading } from './platform.service';

/**
 * Price collars, read off the regulatory journal rather than asserted.
 *
 * A collar is a band anchored on the FIRST limit order that entered a security's book — not a
 * percentage around the current mark. A book anchored by a stray order refuses every realistic
 * price for the rest of the epoch, and nothing repairs it in place.
 *
 * <p>The verdict rule is CONTAINMENT, and it is the part worth reading carefully. A collar refuses
 * on BOTH sides of its band, so the refused prices normally straddle the accepted ones — a test
 * asking "are these two ranges disjoint?" answers "no, so something else refused it" on a working
 * band, every time. It reports the band's own signature as its absence. The discriminating question
 * is whether any REFUSED price lies INSIDE the accepted range.
 */
@Component({
    selector: 'app-bands',
    standalone: false,
    templateUrl: './bands.component.html'
})
export class BandsComponent implements OnInit {
    reading: Reading<RegulatoryEvent[]> | null = null;
    loading = false;

    constructor(private platform: PlatformService) {}

    ngOnInit(): void { this.load(); }

    load(): void {
        this.loading = true;
        this.platform.getRegulatory().subscribe(r => { this.reading = r; this.loading = false; });
    }

    get error(): string | null { return this.reading && !this.reading.ok ? this.reading.error : null; }

    get checks(): BandCheck[] {
        if (!this.reading || !this.reading.ok) { return []; }
        const acc = new Map<string, number[]>();
        const rej = new Map<string, number[]>();
        for (const e of this.reading.value) {
            const into = e.kind === 'ORDER_ACCEPTED' ? acc : e.kind === 'ORDER_REJECTED' ? rej : null;
            if (into && e.security) {
                into.set(e.security, (into.get(e.security) || []).concat(Number(e.price)));
            }
        }
        const out: BandCheck[] = [];
        rej.forEach((rejected, security) => {
            const accepted = acc.get(security) || [];
            const aLo = accepted.length ? Math.min.apply(null, accepted) : 0;
            const aHi = accepted.length ? Math.max.apply(null, accepted) : 0;
            out.push({
                security, accepted: accepted.length, rejected: rejected.length,
                acceptedLo: aLo, acceptedHi: aHi,
                rejectedLo: Math.min.apply(null, rejected), rejectedHi: Math.max.apply(null, rejected),
                verdict: !accepted.length ? 'never-accepted'
                    : rejected.every(p => p < aLo || p > aHi) ? 'anchored-elsewhere'
                    : 'other-refusal'
            });
        });
        return out.sort((a, b) => a.security.localeCompare(b.security));
    }

    verdictClass(v: string): string {
        return v === 'anchored-elsewhere' ? 'bg-success'
            : v === 'other-refusal' ? 'bg-warning text-dark' : 'bg-secondary';
    }

    verdictText(v: string): string {
        return v === 'anchored-elsewhere' ? 'band refused it'
            : v === 'other-refusal' ? 'another cause' : 'never accepted';
    }
}
