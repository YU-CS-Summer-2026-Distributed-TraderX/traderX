import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from 'main/environments/environment';
import { AlgoParentOrder, AlgoCreateRequest } from '../model/order.model';

/**
 * YU17: execution-algo-engine parent orders (YU08).
 *
 * <p>A parent is handed to the algo engine, which slices it into child orders on a TWAP or VWAP
 * schedule; every child goes through the same gateway and consensus path as a manually entered
 * order, and rests in the book as a live limit order until something crosses it.
 *
 * <p><b>Fill feedback: FIXED as of {@code execution-algo-engine:yu17-fills} (2026-08-19).</b> The
 * {@code :yu15} build consumed the single-BLP tier's per-account order subject and order-id form,
 * neither of which the cluster tier produces, so every parent stayed RUNNING with unfilled buckets
 * even after its children demonstrably traded. New parents now mark buckets filled and complete.
 *
 * <p><b>Parents created before that roll are stranded — measured, not assumed:</b> they replayed
 * through the engine restart with every child id byte-identical, but their fill events were
 * broadcast hours earlier and nothing replays {@code /orders}, so no fill will ever reach them. A
 * mixed list — old parents RUNNING for ever, new ones completing — is the fix landing, not drift.
 * Anything started from the ticket today behaves correctly.
 */
@Injectable({
    providedIn: 'root'
})
export class AlgoService {
    private readonly algoUrl = `${environment.algoUrl}/orders`;

    constructor(private http: HttpClient) {}

    createParent(request: AlgoCreateRequest): Observable<AlgoParentOrder> {
        return this.http.post<AlgoParentOrder>(this.algoUrl, request);
    }

    listParents(): Observable<AlgoParentOrder[]> {
        return this.http.get<AlgoParentOrder[]>(this.algoUrl);
    }

    getParent(parentOrderId: string): Observable<AlgoParentOrder> {
        return this.http.get<AlgoParentOrder>(`${this.algoUrl}/${parentOrderId}`);
    }
}
