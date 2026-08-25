import { Component, OnInit } from '@angular/core';
import { PlatformService, Reading } from './platform.service';

/**
 * Gateway counters, aggregated across every gateway rather than sampled from one.
 *
 * <p>Reading ONE gateway through the Service answers from an arbitrary member and the caller cannot
 * tell which, so a counter read twice can go backwards for no reason. The console server aggregates
 * across all of them; this view renders that, and shows the series name so a number is never
 * separated from what produced it.
 */
@Component({
    selector: 'app-platform-metrics',
    standalone: false,
    templateUrl: './metrics.component.html'
})
export class PlatformMetricsComponent implements OnInit {
    reading: Reading<string> | null = null;
    loading = false;
    filter = 'traderx';

    constructor(private platform: PlatformService) {}

    ngOnInit(): void { this.load(); }

    load(): void {
        this.loading = true;
        this.platform.getGatewayMetrics().subscribe(r => { this.reading = r; this.loading = false; });
    }

    get error(): string | null { return this.reading && !this.reading.ok ? this.reading.error : null; }

    /** Prometheus exposition -> name/value rows. Comments and blank lines are not data. */
    get series(): { name: string; value: string }[] {
        if (!this.reading || !this.reading.ok) { return []; }
        const out: { name: string; value: string }[] = [];
        const want = this.filter.trim().toLowerCase();
        for (const raw of (this.reading.value || '').split('\n')) {
            const line = raw.trim();
            if (!line || line.charAt(0) === '#') { continue; }
            const sp = line.lastIndexOf(' ');
            if (sp < 1) { continue; }
            const name = line.slice(0, sp);
            if (want && name.toLowerCase().indexOf(want) < 0) { continue; }
            out.push({ name: name, value: line.slice(sp + 1) });
        }
        return out;
    }
}
