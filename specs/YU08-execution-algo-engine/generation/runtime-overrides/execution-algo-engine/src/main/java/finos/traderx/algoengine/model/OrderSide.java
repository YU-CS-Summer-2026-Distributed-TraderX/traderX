package finos.traderx.algoengine.model;

/** Same shape as order-matcher's {@code OrderSide} — kept as a separate enum so this component has
 * no compile-time dependency on order-matcher's module. */
public enum OrderSide {
  Buy,
  Sell
}
