/**
 * Where every feature of the current console goes. One table drives the tabs, the ☰ menus and the
 * "moved from" notes, so the navigation and the migration map cannot disagree.
 *
 * `from` names the current console tab and panel (web-front-end-console/src/app). `built` says
 * whether this prototype renders the feature with fixtures ('fixture') or only places it
 * ('mapped'): a mapped entry opens a page that says where the feature lives today.
 */
import type { Workspace } from './session';

export interface Feature {
  id: string;
  title: string;
  /** Plain one-line purpose, written for the person using it. */
  purpose: string;
  from: string;
  built: 'fixture' | 'mapped' | 'proposed';
  /** Primary tab id, or 'menu' for the ☰ menu. */
  place: string;
  ws: Workspace;
}

export const TRADER_TABS = [
  { id: 'overview', label: 'Overview' },
  { id: 'markets', label: 'Markets' },
  { id: 'orders', label: 'Orders' },
  { id: 'positions', label: 'Positions' },
  { id: 'risk', label: 'Risk' },
];

export const ADMIN_TABS = [
  { id: 'health', label: 'Operations' },
  { id: 'accounts', label: 'Accounts' },
  { id: 'operations', label: 'End of day' },
  { id: 'observability', label: 'Observability' },
  { id: 'controls', label: 'Controls' },
];

export const FEATURES: Feature[] = [
  // ---- trader: primary tabs ----
  { id: 'account-summary', title: 'Account summary and alerts', purpose: 'What needs attention on the selected account.', from: 'new (uses blotter and activity data)', built: 'fixture', place: 'overview', ws: 'trader' },
  { id: 'watchlist', title: 'Watchlist', purpose: 'Your own list of instruments with price, source and age.', from: 'new', built: 'fixture', place: 'markets', ws: 'trader' },
  { id: 'instrument', title: 'Instrument details', purpose: 'Terms, price unit, price source and age.', from: 'Trading › Order entry (live price line, bond terms, OCC decode)', built: 'fixture', place: 'markets', ws: 'trader' },
  { id: 'ticket', title: 'Order ticket', purpose: 'Enter orders of every supported type and time in force.', from: 'Trading › Order entry', built: 'fixture', place: 'orders', ws: 'trader' },
  { id: 'band-warning', title: 'Price band warning', purpose: 'Warns before sending a price the book\'s band will refuse.', from: 'Trading › Order entry (band warning)', built: 'mapped', place: 'orders', ws: 'trader' },
  { id: 'otc-ticket', title: 'Swap and swaption tickets', purpose: 'Book contract terms; no price by design.', from: 'Trading › Order entry (Swap, Swaption)', built: 'mapped', place: 'orders', ws: 'trader' },
  { id: 'working', title: 'Working orders, cancel and change', purpose: 'Orders still working, with cancel and quantity/price change.', from: 'Trading › Blotter (open orders) and Activity (cancel)', built: 'fixture', place: 'orders', ws: 'trader' },
  { id: 'order-history', title: 'All order states', purpose: 'Rejected, cancelled and filled orders with reasons.', from: 'Trading › Blotter "all states" toggle', built: 'fixture', place: 'orders', ws: 'trader' },
  { id: 'activity', title: 'Session activity', purpose: 'What you did in this session and what came back.', from: 'Trading › Activity & rejections', built: 'fixture', place: 'orders', ws: 'trader' },
  { id: 'trace', title: 'Order trace', purpose: 'The span timeline of one order.', from: 'Trading › Activity / Blotter row details', built: 'mapped', place: 'orders', ws: 'trader' },
  { id: 'positions', title: 'Positions', purpose: 'Holdings with price source, age, value and unrealized P&L.', from: 'Trading › Blotter & positions', built: 'fixture', place: 'positions', ws: 'trader' },
  { id: 'fills', title: 'Fills and settlement', purpose: 'Booked trades and their settlement state.', from: 'Trading › Blotter trades', built: 'fixture', place: 'positions', ws: 'trader' },
  { id: 'runs', title: 'Active and historical runs', purpose: 'Look at a retired run, read-only.', from: 'Trading › Blotter run selector', built: 'fixture', place: 'positions', ws: 'trader' },
  { id: 'otc-contracts', title: 'Booked OTC contracts', purpose: 'Swaps and swaptions at contract level.', from: 'Trading › Blotter OTC section', built: 'mapped', place: 'positions', ws: 'trader' },
  { id: 'find', title: 'Find by reference', purpose: 'Jump to an order, trade or trace id.', from: 'Trading › Blotter', built: 'mapped', place: 'positions', ws: 'trader' },
  { id: 'risk-status', title: 'Risk calculation status', purpose: 'Where the overnight calculation is.', from: 'Risk › Calculation status', built: 'fixture', place: 'risk', ws: 'trader' },
  { id: 'risk-coverage', title: 'Calculation coverage', purpose: 'Which measures were calculated, unsupported or not applicable.', from: 'Risk › Calculation coverage', built: 'fixture', place: 'risk', ws: 'trader' },
  { id: 'risk-compare', title: 'Pricing comparison', purpose: 'Results against the independent check.', from: 'Risk › Synthetic pricing comparison', built: 'fixture', place: 'risk', ws: 'trader' },
  // ---- trader: ☰ menu ----
  { id: 'algos', title: 'Algo orders', purpose: 'TWAP and VWAP parents and their slices.', from: 'Admin › trade lifecycle (algo parents) and Trading ticket', built: 'fixture', place: 'menu', ws: 'trader' },
  { id: 'tca', title: 'Trade cost analysis', purpose: 'How a fill compared with the market.', from: 'Trading › Blotter row / Admin (inline TCA)', built: 'mapped', place: 'menu', ws: 'trader' },
  { id: 'replay', title: 'Market replay', purpose: 'The recorded tape: clock, days and collar reference.', from: 'Replay tab (sign-in)', built: 'mapped', place: 'menu', ws: 'trader' },
  { id: 'corpus', title: 'Tape corpus', purpose: 'Every symbol and session in the recorded tape.', from: 'Corpus tab (sign-in)', built: 'mapped', place: 'menu', ws: 'trader' },
  { id: 'sandbox', title: 'Sandbox venue', purpose: 'Replay the tape in a separate venue.', from: 'Sandbox tab (sign-in)', built: 'mapped', place: 'menu', ws: 'trader' },
  { id: 'fix', title: 'FIX session', purpose: 'Send one order over FIX 4.4 and read the wire.', from: 'FIX tab', built: 'mapped', place: 'menu', ws: 'trader' },
  { id: 'presets', title: 'Demo presets', purpose: 'Fill a whole ticket for one demo story.', from: 'Trading › Order entry (Demo preset)', built: 'mapped', place: 'menu', ws: 'trader' },
  { id: 'settings', title: 'Workspace settings', purpose: 'Saved filters and a reset of your own layout.', from: 'new', built: 'fixture', place: 'menu', ws: 'trader' },
  { id: 'data', title: 'Prototype data', purpose: 'Switch fixture scenarios to review each state.', from: 'prototype only', built: 'fixture', place: 'menu', ws: 'trader' },
  { id: 'legacy', title: 'Current console and legacy UI', purpose: 'The existing console stays available unchanged.', from: 'Legacy UI tab', built: 'mapped', place: 'menu', ws: 'trader' },
  // ---- admin: primary tabs ----
  { id: 'eod-chain', title: 'End-of-day chain', purpose: 'Close, publish, value, cut and calculate, in order.', from: 'End of day › chain', built: 'fixture', place: 'operations', ws: 'admin' },
  { id: 'eod-prices', title: 'Price session: draft, override, publish', purpose: 'Check and publish the day\'s prices.', from: 'End of day › prices', built: 'mapped', place: 'operations', ws: 'admin' },
  { id: 'eod-jobs', title: 'Risk jobs', purpose: 'Coordinator job status for each bundle.', from: 'End of day › jobs, Risk › status', built: 'mapped', place: 'operations', ws: 'admin' },
  { id: 'provenance', title: 'Cut provenance', purpose: 'Each cut with its SHA-256 and archive copy.', from: 'End of day › cut provenance', built: 'mapped', place: 'operations', ws: 'admin' },
  { id: 'treasury-demo', title: 'Treasury demo run', purpose: 'The one bounded operator demo run.', from: 'Risk › treasury demo', built: 'mapped', place: 'operations', ws: 'admin' },
  { id: 'members', title: 'Cluster members', purpose: 'Role and applied sequence for each member.', from: 'System › cluster', built: 'fixture', place: 'health', ws: 'admin' },
  { id: 'services', title: 'Service status', purpose: 'One request per service through the edge proxy.', from: 'System › service status; masthead gateway pill', built: 'fixture', place: 'health', ws: 'admin' },
  { id: 'latency', title: 'Latency and throughput', purpose: 'Consensus p50/p99 with sample counts.', from: 'System › metrics', built: 'mapped', place: 'health', ws: 'admin' },
  { id: 'accounts', title: 'Accounts and admission', purpose: 'Create, admit to the engine, suspend.', from: 'Accounts tab', built: 'fixture', place: 'accounts', ws: 'admin' },
  { id: 'entitlements', title: 'User entitlements', purpose: 'Which user may see and trade which account.', from: 'proposed (no current screen)', built: 'proposed', place: 'accounts', ws: 'admin' },
  { id: 'session-driver', title: 'Live trading session', purpose: 'Generate order flow at a chosen rate.', from: 'Admin › live trading session', built: 'mapped', place: 'controls', ws: 'admin' },
  { id: 'bands', title: 'Book bands and refusals', purpose: 'Which books refuse realistic prices, and why.', from: 'Admin › bands', built: 'mapped', place: 'controls', ws: 'admin' },
  { id: 'overrides', title: 'Overrides: force settle, cancel by reference, orphan sweep', purpose: 'Changes that depart from what the system would do.', from: 'Admin panel and Blotter', built: 'fixture', place: 'controls', ws: 'admin' },
  { id: 'risk-control', title: 'Risk control', purpose: 'Halt or resume trading on an account.', from: 'api.ts risk/control (Admin)', built: 'fixture', place: 'controls', ws: 'admin' },
  { id: 'grafana', title: 'Dashboards', purpose: 'The provisioned Grafana dashboards.', from: 'Grafana tab', built: 'mapped', place: 'observability', ws: 'admin' },
  { id: 'kdb', title: 'Tick capture', purpose: 'The kdb capture tap views.', from: 'Kdb tab', built: 'mapped', place: 'observability', ws: 'admin' },
  // ---- admin: ☰ menu ----
  { id: 'recovery', title: 'Runs and recovery', purpose: 'Run registry, active pointer and projection recovery.', from: 'Blotter run selector; recovery docs', built: 'fixture', place: 'menu', ws: 'admin' },
  { id: 'audit', title: 'Audit log', purpose: 'Sign-ins and admin actions by user.', from: 'proposed (no current screen)', built: 'proposed', place: 'menu', ws: 'admin' },
  { id: 'sandbox-admin', title: 'Sandbox reset', purpose: 'Reset the sandbox venue for everyone.', from: 'Sandbox tab', built: 'mapped', place: 'menu', ws: 'admin' },
  { id: 'legacy-admin', title: 'Current console', purpose: 'The existing console stays available unchanged.', from: 'Legacy UI tab', built: 'mapped', place: 'menu', ws: 'admin' },
];

export const menuFor = (ws: Workspace) => FEATURES.filter(f => f.ws === ws && f.place === 'menu');
export const featuresOn = (tab: string) => FEATURES.filter(f => f.place === tab);
export const feature = (id: string) => FEATURES.find(f => f.id === id);
