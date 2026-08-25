import { Component, OnInit } from '@angular/core';
import { PlatformService, Reading } from './platform.service';

/**
 * The kdb capture tap: the analytical path off the same output events the blotters are fed from.
 *
 * <p>Read per MEMBER, not per cluster, and that is the point of the view. Every member sees the
 * same committed output, so the captures should agree; a member whose capture is absent while its
 * siblings have one is a capture fault, not a market with nothing in it.
 */
@Component({
    selector: 'app-tickstore',
    standalone: false,
    templateUrl: './tickstore.component.html'
})
export class TickStoreComponent implements OnInit {
    reading: Reading<{ members: { member: number; capture: string }[] }> | null = null;
    loading = false;

    constructor(private platform: PlatformService) {}

    ngOnInit(): void { this.load(); }

    load(): void {
        this.loading = true;
        this.platform.getTickCapture().subscribe(r => { this.reading = r; this.loading = false; });
    }

    get members(): { member: number; capture: string }[] {
        return this.reading && this.reading.ok ? (this.reading.value.members || []) : [];
    }
    get error(): string | null { return this.reading && !this.reading.ok ? this.reading.error : null; }

    /** True when some members captured and others did not — agreement is the reading that matters. */
    get uneven(): boolean {
        const have = this.members.filter(m => !!m.capture).length;
        return have > 0 && have < this.members.length;
    }
}
