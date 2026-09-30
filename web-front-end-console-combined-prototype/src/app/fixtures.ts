/**
 * SYNTHETIC LOCAL FIXTURES — prototype only.
 *
 * Every user, account, instrument, price, order, trade and run below is invented for design review.
 * None of it was read from a rig, and nothing in this prototype sends it anywhere. The source
 * strings deliberately use the publisher's real vocabulary (taq-replay-*, fred-*, black-scholes,
 * simulated-*, previous-close) so the console's own provenance chip classifies them, but the
 * numbers are made up.
 */
import type { OrderType, Tif } from '../../../web-front-end-console/src/app/order-types';

export const FIXTURE_LABEL = 'Synthetic local fixture data. Not live, not sent anywhere.';

export type Role = 'trader' | 'admin';

export interface FixtureUser {
  id: string;
  name: string;
  roles: Role[];
  /** Accounts this identity may read and trade. The real rule belongs on the server (see plan). */
  accounts: number[];
}

export const USERS: FixtureUser[] = [
  { id: 'trader.a', name: 'Avery Stone', roles: ['trader'], accounts: [22214, 42422] },
  { id: 'trader.b', name: 'Blake Rivera', roles: ['trader'], accounts: [51010] },
  { id: 'ops.admin', name: 'Morgan Lee', roles: ['trader', 'admin'], accounts: [22214] },
];

export interface FixtureAccount { id: number; name: string; }
export const ACCOUNTS: FixtureAccount[] = [
  { id: 22214, name: 'Equity desk A' },
  { id: 42422, name: 'Rates desk' },
  { id: 51010, name: 'Options desk' },
];

export type AssetClass = 'Equity' | 'Option' | 'Treasury' | 'Corporate';

export interface FixtureInstrument {
  key: string;
  name: string;
  cls: AssetClass;
  /** Publisher source string, verbatim vocabulary. */
  source: string;
  /** Unit of the price, shown beside it. */
  unit: string;
  /** Quantity unit and multiplier used for market value. */
  qtyUnit: string;
  multiplier: number;
  seed: number;
  /** A price that never moves in the fixture (carried-forward close). */
  frozen?: boolean;
  terms?: string;
}

export const INSTRUMENTS: FixtureInstrument[] = [
  { key: 'IBM', name: 'International Business Machines', cls: 'Equity', source: 'simulated-walk',
    unit: 'USD', qtyUnit: 'shares', multiplier: 1, seed: 182.4 },
  { key: 'AAPL', name: 'Apple Inc.', cls: 'Equity', source: 'taq-replay-2025', unit: 'USD',
    qtyUnit: 'shares', multiplier: 1, seed: 221.9 },
  { key: 'MSFT', name: 'Microsoft Corp.', cls: 'Equity', source: 'taq-replay-2025', unit: 'USD',
    qtyUnit: 'shares', multiplier: 1, seed: 418.2 },
  { key: 'GOOG', name: 'Alphabet Inc. Class C', cls: 'Equity', source: 'previous-close', unit: 'USD',
    qtyUnit: 'shares', multiplier: 1, seed: 171.05, frozen: true },
  { key: 'AAPL261218C00260000', name: 'AAPL 2026-12-18 Call 260', cls: 'Option', source: 'black-scholes',
    unit: 'USD per share', qtyUnit: 'contracts', multiplier: 100, seed: 6.85,
    terms: 'Underlying AAPL · expires 2026-12-18 · call · strike 260 · 100 shares per contract' },
  { key: 'UST-20351115', name: 'US Treasury 4.25% 2035-11-15', cls: 'Treasury', source: 'fred-h15-cmt',
    unit: 'fraction of par', qtyUnit: 'USD face', multiplier: 1, seed: 0.9861,
    terms: 'Coupon 4.25% · ACT/ACT ICMA · matures 2035-11-15' },
  { key: 'ACME-5-2030', name: 'Acme Industrial 5% 2030', cls: 'Corporate', source: 'previous-close',
    unit: 'fraction of par', qtyUnit: 'USD face', multiplier: 1, seed: 1.0134, frozen: true,
    terms: 'Coupon 5% · 30/360 · matures 2030-06-01' },
];

export interface FixturePosition { account: number; key: string; quantity: number; avgCost: number; }

export const POSITIONS: FixturePosition[] = [
  { account: 22214, key: 'IBM', quantity: 300, avgCost: 180.25 },
  { account: 22214, key: 'AAPL', quantity: 100, avgCost: 221.4 },
  { account: 22214, key: 'GOOG', quantity: -40, avgCost: 172.1 },
  { account: 42422, key: 'UST-20351115', quantity: 1_000_000, avgCost: 0.9812 },
  { account: 42422, key: 'ACME-5-2030', quantity: 250_000, avgCost: 1.0101 },
  { account: 51010, key: 'AAPL261218C00260000', quantity: 10, avgCost: 6.4 },
  { account: 51010, key: 'MSFT', quantity: 50, avgCost: 410.0 },
];

export type OrderStatus = 'NEW' | 'PARTIALLY_FILLED' | 'PENDING_TRIGGER' | 'SUSPENDED' | 'FILLED' | 'CANCELED' | 'REJECTED';

export interface FixtureOrder {
  ref: number; account: number; key: string; side: 'Buy' | 'Sell'; quantity: number; filled: number;
  orderType: OrderType | 'UNTYPED'; tif: Tif | '—'; limitPrice?: number; stopPrice?: number;
  status: OrderStatus; reason?: string; createdAt: string; updatedAt: string;
}

const today = '2026-09-25';
export const ORDERS: FixtureOrder[] = [
  { ref: 101, account: 22214, key: 'IBM', side: 'Buy', quantity: 100, filled: 0, orderType: 'LIMIT', tif: 'GTC',
    limitPrice: 178.5, status: 'NEW', createdAt: `${today} 09:41:07`, updatedAt: `${today} 09:41:07` },
  { ref: 102, account: 22214, key: 'AAPL', side: 'Sell', quantity: 100, filled: 0, orderType: 'STOP', tif: 'DAY',
    stopPrice: 210, status: 'PENDING_TRIGGER', createdAt: `${today} 09:44:52`, updatedAt: `${today} 09:44:52` },
  { ref: 103, account: 22214, key: 'MSFT', side: 'Buy', quantity: 50, filled: 0, orderType: 'LIMIT', tif: 'FOK',
    limitPrice: 405, status: 'REJECTED', reason: 'FOK_UNFILLABLE', createdAt: `${today} 10:02:15`, updatedAt: `${today} 10:02:15` },
  { ref: 104, account: 42422, key: 'UST-20351115', side: 'Buy', quantity: 500_000, filled: 200_000, orderType: 'LIMIT',
    tif: 'DAY', limitPrice: 0.9858, status: 'PARTIALLY_FILLED', createdAt: `${today} 09:58:30`, updatedAt: `${today} 10:11:04` },
  { ref: 105, account: 51010, key: 'AAPL261218C00260000', side: 'Sell', quantity: 5, filled: 0, orderType: 'LIMIT',
    tif: 'GTC', limitPrice: 7.2, status: 'NEW', createdAt: `${today} 10:05:41`, updatedAt: `${today} 10:05:41` },
];

export interface FixtureTrade {
  id: string; account: number; key: string; side: 'Buy' | 'Sell'; quantity: number; price: number;
  state: 'Processing' | 'Settled'; sourceOrder: string; bookedAt: string;
}

export const TRADES: FixtureTrade[] = [
  { id: 'T-7781', account: 22214, key: 'IBM', side: 'Buy', quantity: 300, price: 180.25, state: 'Settled',
    sourceOrder: 'e7-88', bookedAt: '2026-09-23 14:12:09' },
  { id: 'T-7802', account: 22214, key: 'AAPL', side: 'Buy', quantity: 100, price: 221.4, state: 'Processing',
    sourceOrder: 'e7-97', bookedAt: '2026-09-24 11:03:44' },
  { id: 'T-7810', account: 22214, key: 'GOOG', side: 'Sell', quantity: 40, price: 172.1, state: 'Processing',
    sourceOrder: 'e7-99', bookedAt: '2026-09-24 15:40:18' },
  { id: 'T-7815', account: 42422, key: 'UST-20351115', side: 'Buy', quantity: 200_000, price: 0.9857, state: 'Processing',
    sourceOrder: 'e8-104', bookedAt: `${today} 10:11:04` },
  { id: 'T-7760', account: 42422, key: 'UST-20351115', side: 'Buy', quantity: 800_000, price: 0.9801, state: 'Settled',
    sourceOrder: 'e7-61', bookedAt: '2026-09-22 10:20:31' },
  { id: 'T-7799', account: 51010, key: 'AAPL261218C00260000', side: 'Buy', quantity: 10, price: 6.4, state: 'Processing',
    sourceOrder: 'e7-95', bookedAt: '2026-09-24 10:44:10' },
];

/** Projection runs, same shape as run-scope.ts RunRow. */
export const RUNS = [
  { projection_scope: 'run-20260925-a', cluster_epoch: 'e8', event_id_scheme: 'epoch-seq', descriptor_hash: 'f3a9',
    phase: 'ACTIVE', checkpoint_seq: 18422 },
  { projection_scope: 'run-20260924-a', cluster_epoch: 'e7', event_id_scheme: 'epoch-seq', descriptor_hash: '9c41',
    phase: 'RETIRED', checkpoint_seq: 16107 },
];

/** Historical run rows are frozen: the retired run's positions for each account. */
export const HISTORY_POSITIONS: FixturePosition[] = [
  { account: 22214, key: 'IBM', quantity: 300, avgCost: 180.25 },
  { account: 22214, key: 'AAPL', quantity: 100, avgCost: 221.4 },
  { account: 22214, key: 'GOOG', quantity: -40, avgCost: 172.1 },
  { account: 42422, key: 'UST-20351115', quantity: 800_000, avgCost: 0.9801 },
  { account: 51010, key: 'AAPL261218C00260000', quantity: 10, avgCost: 6.4 },
];

/** Admin fixtures: cluster members and services as the Health tab would show them. */
export const MEMBERS = [
  { id: 0, role: 'LEADER', applied: 18422, engineApplied: 18422, trades: 7815 },
  { id: 1, role: 'FOLLOWER', applied: 18422, engineApplied: 18422, trades: 7815 },
  { id: 2, role: 'FOLLOWER', applied: 18419, engineApplied: 18419, trades: 7815 },
];

export const SERVICES = [
  { name: 'Order gateway', path: '/order-matcher/ready', status: 200 },
  { name: 'Reference data', path: '/reference-data/stocks', status: 200 },
  { name: 'Account service', path: '/account-service/account/', status: 200 },
  { name: 'Position service', path: '/position-service/health/alive', status: 200 },
  { name: 'Trade processor', path: '/trade-processor/actuator/health', status: 200 },
  { name: 'Algo engine', path: '/algo/orders', status: 503 },
];

export const EOD_CHAIN = [
  { step: 'Session closed', state: 'done', detail: 'Business date 2026-09-24 · price version 3' },
  { step: 'Prices published', state: 'done', detail: '7 instruments · 1 overridden (ACME-5-2030)' },
  { step: 'Positions valued', state: 'done', detail: '3 accounts marked' },
  { step: 'Risk extract cut', state: 'done', detail: 'Consensus sequence 16107' },
  { step: 'Risk calculation', state: 'pending', detail: 'Waiting for the overnight batch' },
];
