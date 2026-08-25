import { Component, OnDestroy, OnInit } from '@angular/core';
import { PlatformService, MemberRow, Reading } from './platform.service';

/**
 * The Aeron cluster the order flow is sequenced through — the thing this state exists to build, and
 * the one part of it the original app never had a window onto.
 *
 * The reading that matters is AGREEMENT: three members, one leader, and applied sequences that
 * track each other. A follower drifting is not a slow follower, it is a divergent one, and a mixed
 * build across members diverges permanently rather than catching up.
 */
@Component({
    selector: 'app-cluster',
    standalone: false,
    templateUrl: './cluster.component.html'
})
export class ClusterComponent implements OnInit, OnDestroy {
    reading: Reading<MemberRow[]> | null = null;
    lastRefreshUtc: string | null = null;
    private timer: any;

    constructor(private platform: PlatformService) {}

    ngOnInit(): void { this.refresh(); this.timer = setInterval(() => this.refresh(), 5000); }
    ngOnDestroy(): void { clearInterval(this.timer); }

    refresh(): void {
        this.platform.getMembers().subscribe(r => {
            this.reading = r;
            this.lastRefreshUtc = new Date().toISOString();
        });
    }

    get members(): MemberRow[] { return this.reading && this.reading.ok ? this.reading.value : []; }
    get error(): string | null { return this.reading && !this.reading.ok ? this.reading.error : null; }

    /**
     * Whether every started member has applied the same sequence.
     *
     * Members that have not started are excluded rather than counted as disagreeing — a member
     * still catching up has not diverged, and calling that disagreement would cry wolf on every
     * restart. Fewer than two started members is reported as unknown, not as agreement: one member
     * agreeing with itself is not a consensus reading.
     */
    get agreement(): { state: 'agreed' | 'split' | 'unknown'; detail: string } {
        const started = this.members.filter(m => m.started);
        if (started.length < 2) { return { state: 'unknown', detail: `${started.length} member(s) started` }; }
        const applied = started.map(m => m.applied);
        const min = Math.min(...applied), max = Math.max(...applied);
        return min === max
            ? { state: 'agreed', detail: `all ${started.length} at sequence ${max}` }
            : { state: 'split', detail: `spread of ${max - min} across ${started.length} members` };
    }

    get leaders(): MemberRow[] { return this.members.filter(m => (m.role || '').toUpperCase() === 'LEADER'); }

    rowClass(m: MemberRow): string {
        if (!m.started) { return 'table-warning'; }
        return (m.role || '').toUpperCase() === 'LEADER' ? 'table-primary' : '';
    }
}
