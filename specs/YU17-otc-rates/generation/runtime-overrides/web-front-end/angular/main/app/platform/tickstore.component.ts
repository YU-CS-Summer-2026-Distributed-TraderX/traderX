import { Component, OnInit } from '@angular/core';
import { PlatformService, Reading } from './platform.service';

/**
 * The kdb capture tap: the analytical path, written off the same output events the blotters see.
 *
 * <p><b>Only the LEADER writes.</b> A member's row count is therefore the size of its leadership
 * windows, not its share of the work, and a follower with a header-only file is a follower — not a
 * fault. This view previously warned when members disagreed; measured against a healthy cluster
 * that fired immediately (two members header-only, one with 190 rows), which is the normal steady
 * state and not something to alarm on. An alarm that is right only during a failover teaches the
 * reader to ignore it.
 *
 * <p>The bridge returns each capture as raw text with `==FILE &lt;path&gt; &lt;rows&gt;` markers. It
 * is summarised to file and row count here rather than rendered: the raw body is hundreds of CSV
 * lines, which is a data dump rather than a read model, and the tick store itself is where anyone
 * would actually query it.
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

    get error(): string | null { return this.reading && !this.reading.ok ? this.reading.error : null; }

    /** One row per capture file, per member — name and row count, never the body. */
    get files(): CaptureFile[] {
        if (!this.reading || !this.reading.ok) { return []; }
        const out: CaptureFile[] = [];
        for (const m of (this.reading.value.members || [])) {
            const marker = /==FILE\s+(\S+)\s+(\d+)/g;
            let hit = marker.exec(m.capture || '');
            if (!hit) { out.push({ member: m.member, file: '(no capture file)', rows: 0 }); continue; }
            while (hit) {
                const path = hit[1];
                out.push({
                    member: m.member,
                    file: path.substring(path.lastIndexOf('/') + 1),
                    // The marker's count includes the CSV header line, which is not a captured row.
                    rows: Math.max(0, Number(hit[2]) - 1)
                });
                hit = marker.exec(m.capture || '');
            }
        }
        return out;
    }

    get totalRows(): number { return this.files.reduce((n, f) => n + f.rows, 0); }
}

export interface CaptureFile { member: number; file: string; rows: number; }
