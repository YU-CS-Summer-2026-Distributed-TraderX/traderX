-- Bounded settlement range and deterministic order, including retained schemas.
CREATE INDEX IF NOT EXISTS idx_trades_settlement_due ON trades(state,settlementdate,id);
