import { test } from 'node:test';
import assert from 'node:assert/strict';
import { isOperatorControl } from './operator-control.mjs';
test('refuses every spelling of the operator control routes', () => {
  for (const u of ['/trade-processor/v2/projection-control/select', '/trade-processor/v2/projection-control',
    '/trade-processor//v2/projection-control/activate', '/Trade-Processor/V2/Projection-Control/state/x',
    '/trade-processor/v2/projection-control%2Fprepare', '/trade-processor/v2%2Fprojection-control/freeze',
    '/trade-processor/v2/projection%252Dcontrol/verify', '/order-matcher/run/control', '/trade-processor/v2/projection-recovery/catch-up', '/order-matcher/run/control?x=1',
    '/trade-processor/v2/%E0%A4%A', '/legacy/trade-processor/v2/projection-control/select',
    '/legacy//order-matcher/run/control', '/gw/0/run/control']) {
    assert.equal(isOperatorControl(u), true, u);
  }
});
test('leaves the read routes the console uses alone', () => {
  for (const u of ['/position-service/v2/projections', '/position-service/v2/projections/s/accounts/1/trades',
    '/trade-processor/v2/projections/s/accounts/1/orders?status=all', '/trade-processor/accounts/1/orders',
    '/order-matcher/run/status', '/order-matcher/cancel', '/legacy/', '/gw/0/health', '/legacyx/trade-processor/v2/projection-control']) {
    assert.equal(isOperatorControl(u), false, u);
  }
});
