-- PROOF FIXTURE ONLY (disposable DB). The generated ConfigMap initializer seeds demo trades/positions
-- that exist in no Aeron archive; RI-06 verify therefore (correctly) refuses with
-- RUN_TRADE_POPULATION_MISMATCH. RunMigrationLiveIT starts from an empty schema; this matches it.
DELETE FROM trades; DELETE FROM positions; DELETE FROM orderbook;
