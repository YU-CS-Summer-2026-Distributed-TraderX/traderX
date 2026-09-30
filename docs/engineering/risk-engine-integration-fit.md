---
title: External risk-engine integration
---

# External risk-engine integration

TraderX owns order, trade, position and EOD export behavior. The external engine owns financial models and supported calculations. Integration joins those systems through versioned input and result contracts rather than copying engine implementation into TraderX.

The current boundary includes immutable portfolio bundles, instrument terms, dated market inputs, private coordinator custody and validated result intake. A contract can be structurally valid while pricing is unavailable; the UI preserves that distinction.

The accepted container path prices a closed synthetic Treasury-bill profile with an assumed curve. It does not accept arbitrary live portfolios. OTC booking support does not establish matching external pricing support, and missing SOFR conventions must not be translated into an incompatible swap model to obtain a number.

See [EOD integration and recovery boundaries](integration-and-recovery.md), the [feature map](feature-map.md#yu18-risk-integration) and [verification commands](testing-strategy.md). These public guides replace the older correspondence-oriented discussion; implementation contracts remain in the YU18 spec pack.
