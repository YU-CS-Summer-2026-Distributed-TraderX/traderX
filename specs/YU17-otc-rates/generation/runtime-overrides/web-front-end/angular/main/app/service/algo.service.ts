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
 * <p><b>Known engine behaviour on this tier</b> (filed as an open issue): the engine does not
 * observe its children's fills — it consumes the single-BLP tier's per-account order subject and
 * order-id form, neither of which the cluster tier produces — so a parent stays RUNNING and its
 * buckets read unfilled even after the children demonstrably trade. Executions are real and show
 * up in the order and trade blotters; only the engine's own bucket accounting is blind. The UI
 * says so rather than implying the fills did not happen.
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
