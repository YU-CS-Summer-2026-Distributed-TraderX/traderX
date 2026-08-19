import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { environment } from 'main/environments/environment';
import { OrderRecord, OrderCreateRequest } from '../model/order.model';
import { TradeFeedService } from './trade-feed.service';

/**
 * YU17: repointed at the CLUSTER TIER's gateway dialect.
 *
 * <p>This service was written against the single-BLP order-matcher's REST API, which the cluster
 * gateway does not serve. Three of its four calls were wrong here, and the first was dangerous:
 *
 * <ul>
 *   <li><b>Cancel.</b> {@code POST /orders/{id}/cancel} has no context on the gateway, so
 *       HttpServer's longest-prefix routing handed it to <b>/orders</b> — the NEW ORDER handler.
 *       That is the exact failure the gateway's own source calls out ("a cancel that silently
 *       books an order is the worst available failure mode") and the reason it exposes a sibling
 *       {@code /cancel} route taking {@code {orderRef}}. Measured on the rig before the fix:
 *       {@code GET /orders} 405, and the cancel path reaching the order handler.</li>
 *   <li><b>Open orders.</b> {@code GET /orders} answers {@code 405 {"error":"POST only"}} — the
 *       gateway holds no queryable order state; the book lives in the members. The blotter is
 *       therefore built from the live order feed rather than a snapshot fetch (see below).</li>
 *   <li><b>Force fill.</b> No such route on this tier by design: the engine's book decides fills.
 *       Kept as an explicit refusal rather than a 404 nobody can interpret.</li>
 * </ul>
 *
 * <p><b>Wire shapes differ too, and silently.</b> The gateway answers an order with
 * {@code {orderRef, kind, reason?}}, and the leader-side bridge publishes updates keyed
 * {@code id} (epoch-qualified, e.g. {@code "1-2549"}) — neither is the {@code OrderRecord} this
 * app models. Both are normalised here, in one place, so components keep speaking OrderRecord.
 */
@Injectable({
    providedIn: 'root'
})
export class OrderAdminService {
    private readonly ordersUrl = `${environment.orderMatcherUrl}/orders`;
    private readonly cancelUrl = `${environment.orderMatcherUrl}/cancel`;

    /**
     * orderRef -> clientOrderId for orders THIS browser session created. The bridge's order
     * updates do not carry the client id, but the gateway keys an order's trace off it, so
     * without this map a traced order would be looked up under the wrong id (the key-less
     * mix(orderRef) form). Orders created elsewhere stay unmapped and fall back to that form,
     * which the details panel labels as derived-from-ref.
     */
    private readonly clientOrderIdByRef = new Map<number, string>();

    constructor(private http: HttpClient,
                private tradeFeed: TradeFeedService) {}

    /**
     * Open-order snapshot. NOT from the gateway — it answers {@code 405 POST only}, holding no
     * queryable order state — but from trade-processor's order read model, which is fed by the
     * same bridge that publishes the live updates and returns the identical {@code id}-keyed
     * shape (normalised below). Without a snapshot the blotter could only show orders that
     * arrived while it happened to be mounted, so anything entered before opening the tab was
     * invisible; that was measured, not theorised.
     *
     * <p>All-accounts mode has no server-side equivalent (the endpoint is per account), so it
     * starts empty and fills from the live feed.
     */
    getOpenOrders(accountId?: number): Observable<OrderRecord[]> {
        if (accountId == null || accountId <= 0) {
            return of([]);
        }
        return this.http.get<any[]>(`${environment.tradeProcessorUrl}/accounts/${accountId}/orders`).pipe(
            map((rows) => (rows ?? [])
                .map((row) => OrderAdminService.normalizeOrder(row))
                .filter((order): order is OrderRecord => order != null)
                .map((order) => {
                    if (!order.clientOrderId) {
                        order.clientOrderId = this.clientOrderIdFor(order.orderId);
                    }
                    return order;
                })),
            catchError(() => of([]))
        );
    }

    createOrder(order: OrderCreateRequest): Observable<OrderRecord> {
        return this.http.post<any>(this.ordersUrl, order).pipe(
            map((response) => {
                const ref = Number(response?.orderRef);
                if (order.clientOrderId && Number.isFinite(ref) && ref > 0) {
                    this.clientOrderIdByRef.set(ref, order.clientOrderId);
                }
                return this.fromGatewayAck(response, order);
            }),
            catchError(this.handleError)
        );
    }

    /** The client id this session used for an order, if it created it. */
    clientOrderIdFor(orderId: string | number): string | undefined {
        const ref = OrderAdminService.toOrderRef(orderId);
        return ref == null ? undefined : this.clientOrderIdByRef.get(ref);
    }

    /** {@code orderRef} is the gateway's own numeric handle; ids from the feed are "epoch-ref". */
    cancelOrder(orderId: string | number): Observable<OrderRecord> {
        const orderRef = OrderAdminService.toOrderRef(orderId);
        if (orderRef == null) {
            return throwError(() => new Error(`Unparseable order id: ${orderId}`));
        }
        return this.http.post<any>(this.cancelUrl, { orderRef }).pipe(
            map((response) => ({
                orderId: String(orderId),
                accountId: 0,
                security: '',
                side: 'Buy',
                quantity: 0,
                remainingQuantity: 0,
                limitPrice: 0,
                status: response?.canceled ? 'CANCELED' : 'NEW',
                createdAt: '',
                updatedAt: new Date().toISOString()
            } as OrderRecord)),
            catchError(this.handleError)
        );
    }

    forceFillOrder(_orderId: string): Observable<OrderRecord> {
        return throwError(() => new Error(
            'Force fill does not exist on the cluster tier — the matching engine decides fills; ' +
            'an operator can cancel a resting order.'));
    }

    /**
     * Subscribe order updates. The cluster tier publishes EVERY order update on the bare
     * {@code /orders} subject — the per-account {@code /accounts/{id}/orders} form this app was
     * written for is published by trade-processor's REST controller, which gateway-submitted
     * orders never pass through, so that subject never occurs here (measured: a match-all NATS
     * subscription during a live fill saw {@code /orders} and never the per-account form). We
     * therefore always subscribe {@code /orders} and filter by account in the callback.
     */
    subscribeOrders(accountId: number | undefined, callback: (order: OrderRecord) => void): () => void {
        return this.tradeFeed.subscribe('/orders', (raw: any) => {
            const order = OrderAdminService.normalizeOrder(raw);
            if (!order) {
                return;
            }
            if (!order.clientOrderId) {
                order.clientOrderId = this.clientOrderIdFor(order.orderId);
            }
            if (accountId != null && accountId > 0 && order.accountId !== accountId) {
                return;
            }
            callback(order);
        });
    }

    /** Retained for callers that already know their subject. */
    subscribe(topic: string, callback: (order: OrderRecord) => void): () => void {
        return this.tradeFeed.subscribe(topic, callback);
    }

    /**
     * Bridge payload -> OrderRecord. The bridge keys the order {@code id} ("1-2549": epoch and
     * orderRef), where this app models {@code orderId}; without this every live update was
     * dropped by the blotter's {@code if (!order.orderId) return} guard.
     */
    static normalizeOrder(raw: any): OrderRecord | null {
        if (!raw || typeof raw !== 'object') {
            return null;
        }
        const orderId = raw.orderId ?? raw.id;
        if (!orderId) {
            return null;
        }
        return {
            ...raw,
            orderId: String(orderId),
            accountId: Number(raw.accountId ?? 0),
            quantity: Number(raw.quantity ?? 0),
            remainingQuantity: Number(raw.remainingQuantity ?? 0),
            limitPrice: Number(raw.limitPrice ?? 0),
            createdAt: raw.createdAt ?? '',
            updatedAt: raw.updatedAt ?? raw.createdAt ?? new Date().toISOString()
        } as OrderRecord;
    }

    /** "1-2549" | "2549" -> 2549. The engine's cancel takes the ref, not the epoch-qualified id. */
    static toOrderRef(orderId: string | number): number | null {
        const text = String(orderId ?? '').trim();
        const tail = text.includes('-') ? text.slice(text.lastIndexOf('-') + 1) : text;
        const ref = Number(tail);
        return Number.isFinite(ref) && ref > 0 ? ref : null;
    }

    /** Gateway ack {orderRef, kind, reason?} -> OrderRecord, so components keep one shape. */
    private fromGatewayAck(response: any, request: OrderCreateRequest): OrderRecord {
        const rejected = response?.reason != null;
        return {
            orderId: response?.orderRef != null ? String(response.orderRef) : '',
            accountId: request.accountId,
            security: request.security,
            side: request.side,
            quantity: request.quantity,
            remainingQuantity: rejected ? 0 : request.quantity,
            limitPrice: request.limitPrice,
            status: rejected ? 'REJECTED' : 'NEW',
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
            riskReason: response?.reason
        } as OrderRecord;
    }

    private handleError(error: HttpErrorResponse) {
        console.error(error);
        return throwError(() => error);
    }
}
