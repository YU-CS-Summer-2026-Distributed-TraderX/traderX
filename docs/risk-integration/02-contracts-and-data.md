# Contracts and data requirements

Status: draft designs to agree jointly. Names and fields below are proposed additions unless explicitly marked existing. Create machine-readable schemas and executable examples during M0; these Markdown tables are not an implemented API.

## Existing EOD boundary

The current position extract is schema 3; the separate OTC contracts file is schema 2. `risk.extract.ready` announces `uri`, `sha256`, `contractsUri`, `contractsSha256`, `cutSha256`, `sessionDate`, `priceSnapshotVersion`, `consensusSequence` and counts. Preserve and validate CSV preambles before parsing data rows.

The CSV hash, contracts hash and cut hash cover different bytes. Verify each downloaded artifact against its corresponding hash; do not compare a CSV's bytes with `cutSha256`. Validate shared cut/date/version fields and declared row counts. Pin reference-data inputs too: a cut hash alone does not identify every mark or static term joined during rendering.

Archive readiness in a durable integration job/index. The current announcement is a core NATS publish; do not assume a recovering subscriber can retrieve missed announcements. Discover immutable unprocessed artifacts or use a proven durable notification/outbox contract. Distinguish an empty contracts file from a missing one.

## Proposed exchange objects

| Object | Required content and meaning |
|---|---|
| `PortfolioSnapshot` | Schema version; source system; cluster epoch; live/replay mode; experiment run if any; complete-through consensus sequence; projection/cut identity; accounting conventions; positions and individual OTC contracts; open orders in a separate section; hashes and reference-data version |
| `PortfolioEvent` | Event ID; cluster epoch; consensus sequence; record ordinal; integration cursor/watermark; event type; account; order/trade/contract identifiers; economic terms/changes; effective economic time; source provenance |
| `MarketSnapshot` | Valuation time; availability cutoff; observation IDs/as-of times; spot/FX data; curve/surface references; calendars/fixings/actions; source, quality, synthetic/observed/derived labels; transformations and content hashes |
| `RiskRequest` | Logical request ID/idempotency key; portfolio and market references/hashes; supported scope; calculation type; scenario/model/calibration versions; horizon/measure/confidence; reporting currency; requested precision profile and seed/scenario-set reference |
| `RiskResult` | Request/run IDs; echoed input hashes; actual model/build/device/precision; per-position or contract results; aggregates; risk-factor definitions; coverage and warnings; timestamps; numerical diagnostics; artifact references |
| `PolicyProposal` | Proposal/result/policy IDs; target account; expected epoch and portfolio version; validity window; proposed action with bounds; explanation; before/after estimate; consumed/reserved exposure assumptions |
| `ExperimentManifest` | Run ID; corpus partitions/hashes; train/calibration/evaluation windows; initial positions/cash/open orders; clock/reset policy; order program; execution/fee/latency assumptions; risk policy; versions and seeds |

For listed instruments, preserve stable security identity, signed quantity, multiplier and account. For OTC, preserve contract identity and contractual terms; do not collapse contracts into a ticker-level net quantity. Add order/trade lineage separately because the netted EOD extract does not identify the individual trade that created every position.

## Versioning and identity

The full key includes source/cluster epoch and, for experiments, run ID. `SW-<sequence>`, order reference or trade sequence alone is not globally unique across a reset. Source-provided epoch provenance is required; an adapter must not invent it from the current date. Old artifacts lacking epoch require an explicit import namespace and cannot be joined automatically to a live epoch.

Exact economics travel as integers with named scale or decimal strings. Monetary results can use finite floating-point values with explicit currency/unit/precision. Encode large IDs/sequences as decimal strings across JSON boundaries to avoid JavaScript integer truncation. Define canonical serialization before deriving request hashes.

Idempotency covers portfolio hash, market hash, calculation/model/calibration/scenario versions and precision profile. An identical logical request can return an existing successful result. Changed input bytes under an existing immutable ID are an error. Distinct benchmark repetitions have distinct attempt IDs even when sharing the same workload key.

Unknown schemas, indexes or required conventions fail explicitly. Capability discovery reports supported schema/product/calculation combinations. Partial valuations include supported and unsupported items with reasons and gross exposure/row counts where defined; no silent exclusions or zero substitutions.

## Product conventions to agree

| Product | Terms and market inputs that must be represented |
|---|---|
| Equity/ETF | Stable identifier, currency, share count, spot source/time; actions/dividends for returns and multi-day P&L; FX for reporting |
| Listed option | Underlying, strike, expiry, put/call, exercise style, settlement/deliverable and multiplier; spot, discount curve, dividends and vol surface/assumption; adjusted contracts cannot default to ordinary x100 |
| Treasury/corporate bond | Face quantity, coupon and cashflow schedule, maturity, coupon frequency, day count, calendar/settlement convention, clean/dirty price and accrued interest; credit/spread model for corporates |
| USD-SOFR swap | Pay/receive fixed, notional, explicit effective/maturity dates, fixed and overnight-leg conventions, payment lag, calendar adjustments, compounding/lookback/lockout as applicable, fixings, projection and discount curves |
| European swaption | Underlying swap terms, holder long/short exposure, expiry/exercise time, settlement type, premium/cash treatment where modelled, calibrated or labelled assumed volatility/model parameters |
| Bermudan/American swaption | All underlying/option terms plus explicit exercise schedule or window and pricing method; `exerciseStyle` alone is insufficient for arbitrary exercise rights |

TraderX's current OTC file supplies important terms but not every field above. Existing swaption direction describes the underlying fixed leg, not a separate option long/short flag. Agree and persist missing semantics before enabling those products. Future schema additions must preserve existing booked meanings rather than reinterpret old convention indexes.

Alex's current generic IBOR/ACT365 builder must not receive a USD-SOFR/ACT360 booking through a name-only mapping. `paymentFrequency` in TraderX describes the fixed leg, not the floating index tenor. Exact dates must not be rounded to whole years. Keep curve-time measurement separate from coupon accrual conventions.

Treasury prices in TraderX are fractions of par and quantities are face amounts. Position market value is clean; accrued interest is separate. For comparison with discounted cashflows, explicitly reconcile dirty value and valuation/settlement date. Listed-option quantity scaling must apply the contract multiplier exactly once.

## Market data and scenario semantics

Yaakov supplies observations and availability metadata. Alex owns the accepted curve bootstrap, calibration, interpolation and risk-factor mapping; Yaakov can implement data preparation and curve tooling jointly, but there should be one validated numerical definition.

The existing FRED reader holds 11 Treasury CMT observations and interpolates yields. They are not zero-rate pillars or a SOFR curve. Preserve percent-versus-decimal units and each observation's date. Fetch historical observations as of the requested day; the current latest-observation reader is not a historical loader.

A curve package names currency, role (discount/projection), index/collateral assumptions, valuation date, pillar dates/times, zero/discount-factor representation, compounding, day count, interpolation, extrapolation, calibration input IDs and validation diagnostics. A surface additionally names quote convention, expiry/tenor/strike axes and calibration method. Bootstrapping must reprice its calibration instruments within an agreed tolerance.

TAQ packages name the exact product, partitions, symbols, timestamps, timezone/session calendar and filters for corrections, cancels and trade conditions. Only promise bid/ask or depth when the supplied corpus actually includes it. Save an aligned panel, missingness/quality flags and transformations for downstream analytics. Separate realised volatility from implied volatility; equity observations do not supply rates/credit/FX dependence.

Scenario requests distinguish deterministic stress, historical/physical-measure risk forecasts, and risk-neutral pricing/exposure simulation. A risk-neutral simulation is not automatically a calibrated forecast of tomorrow's loss. Record risk horizon, calibration window, sampling frequency, confidence, loss sign and P&L convention. With approximately five months of daily observations, 99% empirical tails are sparse; report effective sample size and uncertainty rather than treating tick count as daily-tail evidence.

For aligned covariance estimation, record factor ordering, units, return definition, missing-data handling, PSD diagnostics and any shrinkage/repair. Do not fit on the test window or combine 2025 equity data with 2026 rates in an ordinary historical run. A deliberately mixed-date stress must be named as such.

## Financial result semantics

- NPV is signed value to the named account/position in a stated currency at the valuation time. Keep observed execution price, observed mark and model value separate.
- Equity delta can mean shares-equivalent or currency sensitivity; name which. Gamma and vega require underlying/factor units and scaling, including whether vega is per 1 percentage-point volatility change.
- Rate sensitivity uses explicit curve ID, pillar and bump definition. Prefer `pvChangeForPlus1bp` for the signed P&L from a +0.0001 absolute rate move. If the UI calls a measure DV01, publish its sign convention. Sum discount/forward contributions only for the specifically defined joint shock.
- Aggregation requires common reporting currency, synchronized FX and compatible factor definitions. Never sum unrelated vegas, curve pillars or raw deltas into a meaningless scalar.
- Scenario P&L is keyed by scenario ID across all instruments. Portfolio VaR/ES comes from aggregated scenario P&L; summing stand-alone VaRs is not the portfolio risk calculation.
- Economic P&L includes the defined cashflows, fees and lifecycle effects. Separate realised, unrealised and total P&L from model-value changes and OMS clean-mark accounting.
- Theta must hold contractual identity fixed, define curve roll and include relevant intervening cashflows. Rebuilding a new spot-starting trade tomorrow is not ageing the original contract.
- Coverage, missing inputs, numerical failure and expired instruments have explicit statuses. Do not emit NaN/Infinity as ordinary JSON numbers or reinterpret them as zero.

## Jobs, freshness and transport

Use a durable logical job state: queued, running, succeeded, partial, failed, cancelled or superseded. Preserve every attempt and failure reason. Alex's existing API remains an adapter target initially; its in-memory job ID is not the durable system-of-record identity. His process restart must be detectable and recoverable through a bounded retry of immutable inputs.

Separate transport success, financial coverage, projection completeness, data freshness and eligibility for automated action. A successfully computed old result is still historical. Compare source versions before replacing a displayed result or applying a policy; completion order is not portfolio order.

Suggested platform routes are `/risk/jobs`, `/risk/results/{id}`, `/risk/portfolios/{id}`, `/risk/what-if`, `/risk/experiments` and `/risk/policies`. Suggested notifications are `risk.result.ready` and `risk.result.failed`. None are current TraderX endpoints. Schemas, access controls and durable delivery semantics must be implemented together.

Return compact summaries and paginated per-instrument results. Store large scenario arrays separately in chunked artifacts with shape, dtype, ordering and hashes. Avoid sending the entire scenario cube through the browser or a control message.

## Policy decision contract

A proposal records expected holdings and outstanding orders, source versions, desired target exposure, proposed orders, maximum quantity/notional/participation, expiry, cooldown and reason. The OMS/algo controller owns authorization and execution; Alex returns analytics, not broker credentials or direct order side effects.

Check the proposal against current state atomically where required. Incorporate partial fills and in-flight actions before creating another hedge. On gap/stale/unsupported inputs, suppress new automatic risk-increasing actions and follow the explicitly configured handling of existing orders; do not guess that every cancel is risk-reducing. A confirmed disable/kill action overrides the policy.

An accepted control is quantized with an agreed scale/rounding rule before it enters consensus. The replayed command payload is authoritative. Keep computation reproducibility (within declared numerical tolerance) distinct from byte-identical replay of the accepted command.
