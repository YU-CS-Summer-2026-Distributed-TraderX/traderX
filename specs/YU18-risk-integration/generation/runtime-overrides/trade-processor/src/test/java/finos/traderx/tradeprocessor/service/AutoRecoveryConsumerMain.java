package finos.traderx.tradeprocessor.service;

/** Test-only child-JVM entry point: the real TradeProcessorApplication, plus (when
 * AUTO_RECOVERY_HALT_AT names a worker point) a hook that HALTS the JVM there - a real process
 * death at an exact commit boundary, not an exception. Production registers no hooks. */
public final class AutoRecoveryConsumerMain {
  public static void main(String[] args) {
    String at = System.getenv("AUTO_RECOVERY_HALT_AT");
    new org.springframework.boot.builder.SpringApplicationBuilder(finos.traderx.tradeprocessor.TradeProcessorApplication.class)
        .initializers(ctx -> {
          if (at != null && !at.isBlank()) {
            ((org.springframework.context.support.GenericApplicationContext) ctx).registerBean(AutomaticProjectionRecovery.Hooks.class,
                () -> point -> { if (point.equals(at)) { System.out.println("AUTO_RECOVERY_HALT " + point); System.out.flush(); Runtime.getRuntime().halt(137); } });
          }
        }).run(args);
  }
}
