import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

/**
 * The read-model surfaces this state added to the platform, for the ORIGINAL TraderX app.
 *
 * <h2>Why the URLs here are root-absolute and must stay that way</h2>
 * This app is served under <code>/legacy/</code> and ships <code>&lt;base href="."&gt;</code>, so a
 * RELATIVE request resolves to <code>/legacy/&lt;path&gt;</code> — which the console's server strips
 * and forwards to the edge proxy, where none of these routes exist. Every path below therefore
 * starts with "/" so it reaches the console server directly, the same origin that serves this app.
 * The ingress sends <code>/*</code> to that server, so there is exactly one origin and no CORS.
 *
 * <h2>Why every call swallows its error into a typed "unavailable"</h2>
 * These are read models of a live cluster, and several answer only when the thing they describe is
 * running. A view that throws turns "the tick capture is off on this tier" into a broken page. Each
 * method resolves to a value carrying its own failure, so a component can say what is missing
 * instead of showing an empty table that looks like an answer.
 */
@Injectable({ providedIn: 'root' })
export class PlatformService {
    constructor(private http: HttpClient) {}

    /** Aeron cluster members: role, applied sequence, and what each has booked. */
    getMembers(): Observable<Reading<MemberRow[]>> {
        return this.read<{ members: { ordinal: number; code: number; health: MemberHealth }[] }>('/members')
            .pipe(map(r => r.ok
                ? ok((r.value.members || []).map(m => ({ ordinal: m.ordinal, code: m.code, ...m.health })))
                : fail<MemberRow[]>(r.error)));
    }

    /** The four-stage end-of-day chain for a session date. */
    getEodChain(date: string): Observable<Reading<EodChain>> {
        return this.read<EodChain>(`/eod/chain?date=${encodeURIComponent(date)}`);
    }

    /**
     * Objects in the archive bucket, unsorted — the caller decides what "newest" means.
     *
     * Deliberately NOT sorted here. A lexicographic sort puts `proof/<millis>/…` above every dated
     * session prefix, so "newest first" hands back an upload-proof object as the latest cut. That
     * bug reached the screen once already. Ordering needs the date and version parsed out, which is
     * `sessionCuts` below.
     */
    getArchivedCuts(): Observable<Reading<string[]>> {
        return this.read<{ files: string[] }>('/gcs/extracts')
            .pipe(map(r => r.ok ? ok(r.value.files || []) : fail<string[]>(r.error)));
    }

    /**
     * Session cuts newest first, by (date, version) NUMERICALLY — v10 must beat v9, which string
     * order gets wrong. Upload-proof objects are excluded: they are evidence the bucket is
     * writable, not a session's cut, and they carry no session date to order by.
     */
    static sessionCuts(files: string[]): string[] {
        return files
            .filter(f => f.indexOf('/proof/') < 0)
            .map(f => {
                const m = /\/(\d{4}-\d{2}-\d{2})\/v(\d+)\//.exec(f);
                return m ? { f: f, date: m[1], version: Number(m[2]) } : null;
            })
            .filter(x => !!x)
            .sort((a: any, b: any) => b.date === a.date ? b.version - a.version : b.date.localeCompare(a.date))
            .map((x: any) => x.f);
    }

    /** Upload proofs, kept separate so they cannot be mistaken for a session's cut. */
    static proofObjects(files: string[]): string[] {
        return files.filter(f => f.indexOf('/proof/') >= 0);
    }

    /** Cuts on the risk-extract pod's local sink. Empty is NORMAL when the extract writes to a bucket. */
    getLocalCuts(): Observable<Reading<{ pod: string; files: { path: string; sha256: string }[] }>> {
        return this.read('/extracts');
    }

    /** One archived cut's body, for reading OTC rows the rest of this tier never shows. */
    getCut(path: string): Observable<Reading<{ sha256: string; content: string }>> {
        return this.read(`/gcs/read?path=${encodeURIComponent(path)}`);
    }

    /** Per-gateway latency decomposition and order counters. */
    getGatewayMetrics(): Observable<Reading<string>> {
        return this.readText('/order-matcher/metrics');
    }

    /** The kdb capture tap, per member. */
    getTickCapture(): Observable<Reading<{ members: { member: number; capture: string }[] }>> {
        return this.read('/kdbtap');
    }

    /**
     * The regulatory journal: every order the engine ACCEPTED or REFUSED, as agreed by consensus.
     *
     * The path is `/regulatory/report`, not `/regulatory` — and the console server attaches the
     * operator credential to it, so this app never holds one.
     */
    getRegulatory(): Observable<Reading<RegulatoryEvent[]>> {
        return this.read<RegulatoryEvent[]>('/order-matcher/regulatory/report')
            .pipe(map(r => r.ok ? ok(r.value || []) : fail<RegulatoryEvent[]>(r.error)));
    }

    private read<T>(url: string): Observable<Reading<T>> {
        return this.http.get<T>(url).pipe(
            map(v => ok(v)),
            catchError(e => of(fail<T>(describe(e)))));
    }

    /**
     * Text bodies need the shape checked, because a wrong one arrives as a SUCCESS.
     *
     * A JSON request at least fails to parse when an unproxied route answers with the app's own
     * index.html. A text request does not: HTTP 200, a string body, no error anywhere — and a
     * Prometheus parser will then read `<title>FINOS | TraderX` as a series name and render it as
     * a metric. Rows that are real-looking and meaningless are worse than an error, so the shape is
     * asserted here rather than trusted: an exposition never begins with '<'.
     */
    private readText(url: string): Observable<Reading<string>> {
        return this.http.get(url, { responseType: 'text' }).pipe(
            map(v => (v || '').trim().charAt(0) === '<'
                ? fail<string>('this route is not proxied here — it answered with the app\'s own page, not data')
                : ok(v)),
            catchError(e => of(fail<string>(describe(e)))));
    }
}

/**
 * A value or the reason there isn't one — never both, and never an empty value standing in for a
 * failure. The whole point is that a view can tell "nothing to show" from "could not look".
 */
export type Reading<T> = { ok: true; value: T } | { ok: false; error: string };
export const ok = <T>(value: T): Reading<T> => ({ ok: true, value });
export const fail = <T>(error: string): Reading<T> => ({ ok: false, error });

/**
 * Prefer the server's own words; fall back to the status, never to a guess about the cause.
 *
 * <p><b>A 2xx in the error path is not a status, it is a parse failure</b>, and saying "HTTP 200"
 * makes a reader hunt for a server fault that did not happen. It has one overwhelmingly likely
 * cause here: the path was not proxied, so a dev server answered it with index.html — the SPA
 * fallback returns the app's own page, with a 200, for every route it does not know. Reported as
 * what it is, because the fix is a proxy entry and no amount of staring at the cluster reveals that.
 */
function describe(e: any): string {
    const body = e?.error;
    const status = e?.status;
    if (body && typeof body === 'object' && typeof body.error === 'string') { return body.error; }
    if (typeof body === 'string' && body && body.trim().charAt(0) === '<') {
        return 'this route is not proxied here — it answered with the app\'s own page, not data';
    }
    if (typeof body === 'string' && body) { return body.slice(0, 200); }
    if (status >= 200 && status < 300) {
        return 'this route is not proxied here — a page came back where data was expected';
    }
    return status ? `HTTP ${status}` : 'no response';
}

export interface MemberHealth {
    memberId: number; role: string; started: boolean;
    applied: number; engineApplied: number; trades: number; snapshots: number;
}
export interface MemberRow extends MemberHealth { ordinal: number; code: number; }

export interface EodStage {
    state: string; detail: string;
    rows?: number; pod?: string; bucket?: string; sink?: string; files?: string[];
}
export interface EodChain {
    date: string; businessDate?: string;
    prices: EodStage; pnl: EodStage; extract: EodStage; published: EodStage;
}

export interface RegulatoryEvent { kind: string; security: string; price: number; }

/**
 * A security's refusals read against its acceptances.
 *
 * The verdict rule is CONTAINMENT, not disjointness, and the difference is the whole point. A price
 * collar refuses on BOTH sides of its band, so the refused range normally straddles the accepted
 * one and a "do these ranges overlap?" test reports the band's own signature as its absence. The
 * question that discriminates is whether any REFUSED price falls INSIDE the accepted range: none
 * inside is the band working; one inside means something else refused it.
 */
export interface BandCheck {
    security: string; accepted: number; rejected: number;
    acceptedLo: number; acceptedHi: number; rejectedLo: number; rejectedHi: number;
    verdict: 'never-accepted' | 'anchored-elsewhere' | 'other-refusal';
}
