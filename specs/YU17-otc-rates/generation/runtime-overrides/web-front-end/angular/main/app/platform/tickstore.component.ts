import { Component, OnInit } from '@angular/core';
import { PlatformService, Reading } from './platform.service';

/**
 * The kdb tick capture: the analytical path, written off the same output events the blotters see.
 *
 * <p><b>Only the LEADER writes.</b> A member's row count is the size of its leadership windows, not
 * its share of the work, so a follower with a header-only file is a follower and not a fault. An
 * earlier version of this view warned whenever counts differed and fired instantly against a
 * healthy cluster.
 *
 * <p><b>The VWAP here is OURS, not the market's.</b> Every row was emitted by this engine, so it is
 * the volume-weighted average of our own fills — not a tape. It is also only as complete as the
 * capture: the tap is non-durable and sheds rows under flood by design rather than back-pressuring
 * the engine, so a restart blinds it before the first captured trade and a flood leaves holes
 * inside the range. The row counts are shown beside it for exactly that reason.
 *
 * <p>The bridge returns a bounded TAIL of each file (300 lines), so this is the recent window, not
 * the session. The tick store itself is where anyone would query the whole thing.
 */
@Component({
    selector: 'app-tickstore',
    standalone: false,
    templateUrl: './tickstore.component.html'
})
export class TickStoreComponent implements OnInit {
    reading: Reading<{ members: { member: number; capture: string }[] }> | null = null;
    loading = false;
    files: CaptureFile[] = [];
    trades: TradeRow[] = [];
    orders: OrderRow[] = [];

    constructor(private platform: PlatformService) {}

    ngOnInit(): void { this.load(); }

    load(): void {
        this.loading = true;
        this.platform.getTickCapture().subscribe(r => {
            this.reading = r;
            this.loading = false;
            this.files = []; this.trades = []; this.orders = [];
            if (r.ok) { this.parse(r.value.members || []); }
        });
    }

    get error(): string | null { return this.reading && !this.reading.ok ? this.reading.error : null; }

    /**
     * Each member's capture is one text blob of `==FILE <path> <lines>` sections followed by CSV.
     * Rows are split by which file they came from: txorder and txtrade have different columns and
     * conflating them would silently mis-read every field after the third.
     */
    private parse(members: { member: number; capture: string }[]): void {
        for (const m of members) {
            const parts = (m.capture || '').split(/==FILE\s+/).filter(x => x.trim());
            for (const part of parts) {
                const nl = part.indexOf('\n');
                const head = (nl < 0 ? part : part.slice(0, nl)).trim().split(/\s+/);
                const path = head[0] || '';
                const name = path.substring(path.lastIndexOf('/') + 1);
                const body = nl < 0 ? '' : part.slice(nl + 1);
                const lines = body.split('\n').map(l => l.trim()).filter(l => !!l);
                // The marker's count includes the CSV header line, which is not a captured row.
                this.files.push({ member: m.member, file: name, rows: Math.max(0, Number(head[1]) - 1) });
                if (!lines.length) { continue; }
                const cols = lines[0].split(',');
                for (const line of lines.slice(1)) {
                    const cells = line.split(',');
                    const row: any = {};
                    for (let i = 0; i < cols.length; i++) { row[cols[i]] = cells[i]; }
                    if (name.indexOf('txtrade') === 0) {
                        this.trades.push({
                            member: m.member, seq: Number(row.seq), tradeSeq: Number(row.tradeSeq),
                            account: row.account, sym: row.sym, side: row.side,
                            qty: Number(row.qty), px: Number(row.px), tsMs: Number(row.tsMs)
                        });
                    } else if (name.indexOf('txorder') === 0) {
                        this.orders.push({
                            member: m.member, seq: Number(row.seq), ref: Number(row.ref),
                            account: row.account, sym: row.sym, side: row.side,
                            qty: Number(row.qty), remaining: Number(row.remaining),
                            limitPx: Number(row.limitPx), status: row.status
                        });
                    }
                }
            }
        }
        this.trades.sort((a, b) => b.seq - a.seq);
        this.orders.sort((a, b) => b.seq - a.seq);
    }

    get totalRows(): number { return this.files.reduce((n, f) => n + f.rows, 0); }

    /** Fill VWAP per security: sum(px*qty) / sum(qty), over captured fills only. */
    get vwap(): VwapRow[] {
        const by = new Map<string, { execs: number; volume: number; notional: number; first: number; last: number }>();
        for (const t of this.trades.slice().sort((a, b) => a.seq - b.seq)) {
            if (!t.sym || !isFinite(t.px) || !isFinite(t.qty)) { continue; }
            const cur = by.get(t.sym) || { execs: 0, volume: 0, notional: 0, first: t.px, last: t.px };
            cur.execs += 1; cur.volume += t.qty; cur.notional += t.px * t.qty; cur.last = t.px;
            by.set(t.sym, cur);
        }
        const out: VwapRow[] = [];
        by.forEach((v, sym) => out.push({
            sym, execs: v.execs, volume: v.volume,
            vwap: v.volume ? v.notional / v.volume : 0, first: v.first, last: v.last
        }));
        return out.sort((a, b) => a.sym.localeCompare(b.sym));
    }
}

export interface CaptureFile { member: number; file: string; rows: number; }
export interface TradeRow {
    member: number; seq: number; tradeSeq: number; account: string;
    sym: string; side: string; qty: number; px: number; tsMs: number;
}
export interface OrderRow {
    member: number; seq: number; ref: number; account: string; sym: string;
    side: string; qty: number; remaining: number; limitPx: number; status: string;
}
export interface VwapRow {
    sym: string; execs: number; volume: number; vwap: number; first: number; last: number;
}
