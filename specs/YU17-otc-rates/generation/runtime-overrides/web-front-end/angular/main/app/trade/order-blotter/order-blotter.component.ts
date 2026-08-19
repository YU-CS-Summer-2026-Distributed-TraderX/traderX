import { CellClickedEvent, ColDef, GetRowIdParams, GridApi, GridReadyEvent, RowClickedEvent } from 'ag-grid-community';
import { Component, EventEmitter, Input, OnChanges, OnDestroy, Output, SimpleChanges } from '@angular/core';
import { Account } from 'main/app/model/account.model';
import { OrderRecord } from 'main/app/model/order.model';
import { PriceTick } from 'main/app/model/trade.model';
import { HttpClient } from '@angular/common/http';
import { environment } from 'main/environments/environment';
import { OrderAdminService } from 'main/app/service/order-admin.service';
import { traceIdFor } from 'main/app/service/order-trace';
import { TradeFeedService } from 'main/app/service/trade-feed.service';
import { PriceSnapshotService } from 'main/app/service/price-snapshot.service';

type OrderRow = OrderRecord & {
  marketPrice?: number;
  spreadToStrike?: number;
  accountDisplayName?: string;
};

@Component({
  standalone: false,
  selector: 'app-order-blotter',
  templateUrl: './order-blotter.component.html'
})
export class OrderBlotterComponent implements OnChanges, OnDestroy {
  @Input() account?: Account;
  @Input() allAccountsMode = false;
  @Input() accountNameById: { [accountId: number]: string } = {};
  @Input() securityFilter = '';
  @Output() securitySelected = new EventEmitter<string>();

  rows: OrderRow[] = [];
  gridApi?: GridApi<OrderRow>;
  private orderUnsubscribeFn?: () => void;
  private priceUnsubscribeFn?: () => void;
  private readonly marketPriceByTicker = new Map<string, number>();
  private readonly marketPriceAsOfByTicker = new Map<string, number>();

  private readonly baseColumns: ColDef<OrderRow>[] = [
    { headerName: 'ORDER ID', field: 'orderId' },
    { headerName: 'SECURITY', field: 'security' },
    { headerName: 'SIDE', field: 'side' },
    {
      headerName: 'QTY',
      field: 'quantity',
      headerClass: 'ag-right-aligned-header',
      cellClass: 'ag-right-aligned-cell',
      valueFormatter: ({ value }) => this.formatInteger(value)
    },
    {
      headerName: 'REMAINING',
      field: 'remainingQuantity',
      headerClass: 'ag-right-aligned-header',
      cellClass: 'ag-right-aligned-cell',
      valueFormatter: ({ value }) => this.formatInteger(value)
    },
    {
      headerName: 'STRIKE',
      field: 'limitPrice',
      headerClass: 'ag-right-aligned-header',
      cellClass: 'ag-right-aligned-cell',
      valueFormatter: ({ value }) => this.formatCurrency(value)
    },
    {
      headerName: 'MARKET',
      field: 'marketPrice',
      headerClass: 'ag-right-aligned-header',
      cellClass: 'ag-right-aligned-cell',
      valueFormatter: ({ value }) => this.formatCurrency(value),
      cellStyle: ({ data }) => this.marketStyle(data)
    },
    {
      headerName: 'DELTA',
      field: 'spreadToStrike',
      headerClass: 'ag-right-aligned-header',
      cellClass: 'ag-right-aligned-cell',
      valueFormatter: ({ value }) => this.formatSignedCurrency(value),
      cellStyle: ({ value }) => this.deltaStyle(value)
    },
    { headerName: 'UPDATED', field: 'updatedAt', valueFormatter: ({ value }) => this.toRelativeTime(value) },
    {
      headerName: 'ACTION',
      colId: 'cancel',
      cellRenderer: () => '<button class="btn btn-outline-danger btn-sm">Cancel</button>'
    },
    {
      headerName: 'DETAILS',
      colId: 'details',
      cellRenderer: () => '<button class="btn btn-outline-secondary btn-sm">Details</button>'
    }
  ];

  columnDefs: ColDef<OrderRow>[] = [];

  constructor(
    private orderAdminService: OrderAdminService,
    private tradeFeed: TradeFeedService,
    private priceSnapshots: PriceSnapshotService,
    private http: HttpClient
  ) {
    this.getRowId = this.getRowId.bind(this);
  }

  // ---- YU17: order details + distributed trace ------------------------------------------------
  selectedOrder?: OrderRow;
  traceId?: string;
  traceSpans: Array<{ service: string; name: string; startNs: bigint; durationUs: number }> = [];
  traceMessage = '';

  closeDetails(): void {
    this.selectedOrder = undefined;
    this.traceId = undefined;
    this.traceSpans = [];
    this.traceMessage = '';
  }

  /** The trace id is derived from the order itself (see service/order-trace.ts), so no lookup
   *  table is needed — but a trace only EXISTS in Tempo if the order was sampled. Rejections
   *  always are; accepted orders are sampled 1-in-N, hence the explicit "not sampled" message. */
  loadTrace(): void {
    const order = this.selectedOrder;
    if (!order) {
      return;
    }
    this.traceId = traceIdFor(order.clientOrderId, order.orderId);
    if (!this.traceId) {
      this.traceMessage = 'No trace id derivable for this order.';
      return;
    }
    this.traceMessage = 'Fetching trace...';
    this.traceSpans = [];
    this.http.get<any>(`${environment.tempoUrl}/api/traces/${this.traceId}`).subscribe({
      next: (body) => {
        const spans: Array<{ service: string; name: string; startNs: bigint; durationUs: number }> = [];
        for (const batch of body?.batches ?? []) {
          const service = (batch?.resource?.attributes ?? [])
            .find((attribute: any) => attribute?.key === 'service.name')?.value?.stringValue ?? '?';
          for (const scopeSpan of batch?.scopeSpans ?? []) {
            for (const span of scopeSpan?.spans ?? []) {
              const start = BigInt(span?.startTimeUnixNano ?? 0);
              const end = BigInt(span?.endTimeUnixNano ?? 0);
              spans.push({
                service,
                name: span?.name ?? '?',
                startNs: start,
                durationUs: Number(end - start) / 1000
              });
            }
          }
        }
        spans.sort((a, b) => (a.startNs < b.startNs ? -1 : 1));
        this.traceSpans = spans;
        this.traceMessage = spans.length ? '' : 'Trace exists but carries no spans yet.';
      },
      error: (error) => {
        this.traceSpans = [];
        this.traceMessage = error?.status === 404
          ? (order.clientOrderId
              ? 'Not in Tempo — this order was not sampled (accepted orders are head-sampled; rejections always are).'
              : 'Not in Tempo. This order was not created in this browser session, so its client order id is '
                + 'unknown and the id above was derived from the order ref instead — which only matches if the '
                + 'order carried no client id. Orders entered here are always resolvable.')
          : `Tempo unreachable (HTTP ${error?.status ?? 0}).`;
      }
    });
  }

  spanOffsetUs(span: { startNs: bigint }): number {
    if (this.traceSpans.length === 0) {
      return 0;
    }
    return Number(span.startNs - this.traceSpans[0].startNs) / 1000;
  }

  orderNotional(order: OrderRow): number {
    return (order.quantity ?? 0) * (order.limitPrice ?? 0);
  }

  /** The snapshot carries ISO strings, the live bridge epoch millis — render either as local time. */
  formatTimestamp(value: string | number | undefined): string {
    if (value == null || value === '') {
      return 'n/a';
    }
    const asNumber = typeof value === 'number' ? value : Number(value);
    const date = Number.isFinite(asNumber) && String(value).trim() !== ''
      ? new Date(asNumber)
      : new Date(String(value));
    return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes.securityFilter && !changes.account && !changes.allAccountsMode && !changes.accountNameById) {
      this.applySecurityFilter();
    }
    if (changes.account || changes.allAccountsMode || changes.accountNameById) {
      this.configureColumns();
      this.startScope();
    }
  }

  ngOnDestroy(): void {
    this.orderUnsubscribeFn?.();
    this.priceUnsubscribeFn?.();
  }

  onGridReady(params: GridReadyEvent<OrderRow>): void {
    this.gridApi = params.api;
    this.configureColumns();
    this.applySecurityFilter();
    this.gridApi.sizeColumnsToFit();
  }

  onCellClicked(event: CellClickedEvent<OrderRow>): void {
    const orderId = event.data?.orderId;
    if (!orderId) {
      return;
    }
    if (event.colDef.colId === 'details') {
      this.selectedOrder = this.selectedOrder?.orderId === orderId ? undefined : event.data;
      this.traceId = undefined;
      this.traceSpans = [];
      this.traceMessage = '';
      return;
    }
    if (event.colDef.colId !== 'cancel') {
      return;
    }
    this.orderAdminService.cancelOrder(orderId).subscribe({
      error: (error) => console.error('cancel failed', error)
    });
  }

  onRowClicked(event: RowClickedEvent<OrderRow>): void {
    const security = String(event?.data?.security || '').trim().toUpperCase();
    if (!security) {
      return;
    }
    this.securitySelected.emit(security);
  }

  getRowId(params: GetRowIdParams<OrderRow>): string {
    return params?.data?.orderId ?? 'order-unknown';
  }

  private startScope(): void {
    this.orderUnsubscribeFn?.();
    this.priceUnsubscribeFn?.();
    this.marketPriceByTicker.clear();
    this.marketPriceAsOfByTicker.clear();

    const accountId = this.allAccountsMode ? undefined : this.account?.id;
    if (!this.allAccountsMode && (!accountId || accountId <= 0)) {
      this.rows = [];
      this.setGridRowData([]);
      return;
    }
    this.reloadOpenOrders();
    // The cluster tier publishes every order update on the bare `/orders` subject; the
    // per-account form this was written for is published by a REST path gateway-submitted orders
    // never take, so it never arrives. subscribeOrders() subscribes `/orders` and filters by
    // account, and normalises the bridge's `id` field to `orderId` (without which every update
    // was silently dropped by the guard in applyOrderUpdate).
    this.orderUnsubscribeFn = this.orderAdminService.subscribeOrders(
      this.allAccountsMode ? undefined : accountId,
      (order: OrderRecord) => this.applyOrderUpdate(order));
    this.priceUnsubscribeFn = this.tradeFeed.subscribe('pricing.*', (tick: PriceTick) => this.applyPriceTick(tick));
  }

  private reloadOpenOrders(): void {
    const accountId = this.allAccountsMode ? undefined : this.account?.id;
    if (!this.allAccountsMode && (!accountId || accountId <= 0)) {
      this.rows = [];
      this.setGridRowData([]);
      return;
    }
    this.orderAdminService.getOpenOrders(accountId).subscribe((orders: OrderRecord[]) => {
      this.rows = (orders ?? []).map((order) => this.withLivePricing(order));
      this.setGridRowData(this.rows);
      this.bootstrapSnapshotPrices(this.rows.map((row) => row.security));
    });
  }

  private applyOrderUpdate(order: OrderRecord): void {
    if (!order?.orderId) {
      return;
    }
    const selectedAccountId = this.account?.id;
    if (!this.allAccountsMode && selectedAccountId && order.accountId !== selectedAccountId) {
      return;
    }
    if (this.isTerminalStatus(order.status)) {
      const filtered = this.rows.filter((row) => row.orderId !== order.orderId);
      if (filtered.length === this.rows.length) {
        return;
      }
      this.rows = filtered;
      this.setGridRowData(filtered);
      return;
    }
    const updatedRow = this.withLivePricing(order);
    const existingIndex = this.rows.findIndex((row) => row.orderId === order.orderId);
    const nextRows = [...this.rows];
    if (existingIndex >= 0) {
      nextRows[existingIndex] = updatedRow;
    } else {
      nextRows.unshift(updatedRow);
    }
    nextRows.sort((a, b) => this.toEpochMs(b.updatedAt) - this.toEpochMs(a.updatedAt));
    this.rows = nextRows;
    this.setGridRowData(nextRows);
    this.bootstrapSnapshotPrices([updatedRow.security]);
  }

  private applyPriceTick(tick: PriceTick): void {
    if (!tick?.ticker || tick.price == null) {
      return;
    }
    if (!this.applyMarketPriceUpdate(tick.ticker, tick.price, tick.asOf ?? null)) {
      return;
    }
    this.refreshRowsForTicker(tick.ticker);
  }

  private bootstrapSnapshotPrices(tickers: string[]): void {
    this.priceSnapshots.getPrices(tickers).subscribe((snapshots) => {
      const changedTickers = new Set<string>();
      for (const snapshot of snapshots || []) {
        if (!snapshot || !snapshot.ticker || snapshot.price == null) {
          continue;
        }
        if (this.applyMarketPriceUpdate(snapshot.ticker, snapshot.price, snapshot.asOf ?? null)) {
          changedTickers.add(String(snapshot.ticker || '').trim().toUpperCase());
        }
      }
      if (changedTickers.size === 0) {
        return;
      }
      for (const ticker of changedTickers) {
        this.refreshRowsForTicker(ticker);
      }
    });
  }

  private applyMarketPriceUpdate(ticker: string, price: number, asOf: string | null): boolean {
    const normalizedTicker = String(ticker || '').trim().toUpperCase();
    const numericPrice = Number(price);
    if (!normalizedTicker || !Number.isFinite(numericPrice)) {
      return false;
    }

    const nextEpoch = this.toPriceEpoch(asOf);
    const currentEpoch = this.marketPriceAsOfByTicker.get(normalizedTicker);
    if (nextEpoch != null) {
      if (currentEpoch != null && nextEpoch < currentEpoch) {
        return false;
      }
      this.marketPriceAsOfByTicker.set(normalizedTicker, nextEpoch);
    } else if (currentEpoch != null) {
      return false;
    }

    this.marketPriceByTicker.set(normalizedTicker, numericPrice);
    return true;
  }

  private refreshRowsForTicker(ticker: string): void {
    if (this.rows.length === 0) {
      return;
    }
    const normalizedTicker = String(ticker || '').trim().toUpperCase();
    let changed = false;
    const updated = this.rows.map((row) => {
      if (String(row.security || '').trim().toUpperCase() !== normalizedTicker) {
        return row;
      }
      changed = true;
      return this.withLivePricing(row);
    });
    if (!changed) {
      return;
    }
    this.rows = updated;
    this.setGridRowData(updated);
  }

  private withLivePricing(order: OrderRecord): OrderRow {
    const normalizedTicker = String(order.security || '').trim().toUpperCase();
    const marketPrice = this.marketPriceByTicker.get(normalizedTicker);
    const limitPrice = Number(order.limitPrice ?? 0);
    const spreadToStrike = marketPrice == null ? undefined : Number(marketPrice) - limitPrice;
    const accountDisplayName = this.accountNameById[order.accountId] ?? `#${order.accountId}`;
    return Object.assign({}, order, {
      security: normalizedTicker,
      marketPrice: marketPrice == null ? undefined : Number(marketPrice),
      spreadToStrike,
      accountDisplayName
    });
  }

  private configureColumns(): void {
    const accountColumn: ColDef<OrderRow>[] = this.allAccountsMode
      ? [{ headerName: 'ACCOUNT', field: 'accountDisplayName' }]
      : [];
    this.columnDefs = [...accountColumn, ...this.baseColumns];
    if (!this.gridApi) {
      return;
    }
    if (typeof (this.gridApi as any).setGridOption === 'function') {
      (this.gridApi as any).setGridOption('columnDefs', this.columnDefs);
    } else if (typeof (this.gridApi as any).setColumnDefs === 'function') {
      (this.gridApi as any).setColumnDefs(this.columnDefs);
    }
    this.applySecurityFilter();
  }

  private isTerminalStatus(status: string | undefined): boolean {
    return status === 'FILLED' || status === 'CANCELED' || status === 'REJECTED';
  }

  private toEpochMs(value: string | Date | undefined): number {
    if (!value) {
      return 0;
    }
    const ts = new Date(value).getTime();
    return Number.isFinite(ts) ? ts : 0;
  }

  private toPriceEpoch(asOf: string | null | undefined): number | null {
    if (!asOf) {
      return null;
    }
    const ts = new Date(asOf).getTime();
    return Number.isFinite(ts) ? ts : null;
  }

  private setGridRowData(rows: OrderRow[]): void {
    if (!this.gridApi) {
      return;
    }
    if (typeof (this.gridApi as any).setGridOption === 'function') {
      (this.gridApi as any).setGridOption('rowData', rows);
    } else if (typeof (this.gridApi as any).setRowData === 'function') {
      (this.gridApi as any).setRowData(rows);
    }
    this.applySecurityFilter();
    this.gridApi.sizeColumnsToFit();
  }

  private applySecurityFilter(): void {
    if (!this.gridApi) {
      return;
    }
    const filterValue = String(this.securityFilter || '').trim().toUpperCase();
    if (typeof (this.gridApi as any).setGridOption === 'function') {
      (this.gridApi as any).setGridOption('quickFilterText', filterValue);
      return;
    }
    if (typeof (this.gridApi as any).setQuickFilter === 'function') {
      (this.gridApi as any).setQuickFilter(filterValue);
    }
  }

  private marketStyle(data?: OrderRow): any {
    if (!data || data.marketPrice == null) {
      return {};
    }
    const market = Number(data.marketPrice);
    const strike = Number(data.limitPrice);
    if (!Number.isFinite(market) || !Number.isFinite(strike)) {
      return {};
    }
    const favorable = data.side === 'Buy' ? market <= strike : market >= strike;
    return { color: favorable ? '#14532d' : '#991b1b', fontWeight: 700 };
  }

  private deltaStyle(value: any): any {
    const numeric = Number(value);
    if (!Number.isFinite(numeric) || numeric === 0) {
      return {};
    }
    return { color: numeric >= 0 ? '#14532d' : '#991b1b', fontWeight: 700 };
  }

  private formatCurrency(value: any): string {
    if (value == null || value === '') {
      return '-';
    }
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) {
      return '-';
    }
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 3,
      maximumFractionDigits: 3
    }).format(numeric);
  }

  private formatSignedCurrency(value: any): string {
    if (value == null || value === '') {
      return '-';
    }
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) {
      return '-';
    }
    const abs = new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 3,
      maximumFractionDigits: 3
    }).format(Math.abs(numeric));
    return numeric >= 0 ? `+${abs}` : `-${abs}`;
  }

  private formatInteger(value: any): string {
    if (value == null || value === '') {
      return '-';
    }
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) {
      return '-';
    }
    return new Intl.NumberFormat('en-US', {
      maximumFractionDigits: 0
    }).format(numeric);
  }

  private toRelativeTime(value: string | Date | undefined): string {
    if (!value) {
      return '-';
    }
    const ts = new Date(value);
    if (Number.isNaN(ts.getTime())) {
      return '-';
    }
    const now = new Date();
    const elapsedMs = now.getTime() - ts.getTime();
    const elapsedMins = Math.max(0, Math.floor(elapsedMs / 60000));
    if (elapsedMins < 1) {
      return 'just now';
    }
    if (elapsedMins < 60) {
      return `${elapsedMins} min ago`;
    }
    const hours = Math.floor(elapsedMins / 60);
    return `${hours} hr ago`;
  }
}
