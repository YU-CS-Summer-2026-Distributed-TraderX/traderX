package finos.traderx.ordermatcher.cluster;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Calls actual inherited encoder; no NATS connection or duplicated encoding logic. */
class TradeEpochWireTest {
    private String encode(String epoch) throws Exception {
        Class<?> type = Class.forName(TradeNatsPublisher.class.getName() + "$Rec");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object row = constructor.newInstance();
        for (Object[] entry : new Object[][]{{"tradeSeq", 1L}, {"orderRef", 1},
                {"accountId", 1003}, {"security", "IBM"}, {"side", (byte)0},
                {"qty", 100}, {"tradePx", 136250000L}}) {
            Field field = type.getDeclaredField((String)entry[0]);
            field.setAccessible(true);
            field.set(row, entry[1]);
        }
        Method encode = TradeNatsPublisher.class.getDeclaredMethod("encode", StringBuilder.class, type);
        encode.setAccessible(true);
        return new String((byte[])encode.invoke(new TradeNatsPublisher("unused", "/trades", epoch, 8),
                                                new StringBuilder(), row), StandardCharsets.UTF_8);
    }
    @Test void freshEpochChangesOrderLineageButTradePrimaryKeyCollides() throws Exception {
        String old = encode("old"), fresh = encode("fresh");
        System.out.println("RI06_OLD=" + old);
        System.out.println("RI06_FRESH=" + fresh);
        assertTrue(old.contains("\"sourceOrderId\":\"old-1\""));
        assertTrue(fresh.contains("\"sourceOrderId\":\"fresh-1\""));
        // Characterization of the known defect; paired SQL test asserts desired safety and fails.
        assertTrue(old.contains("\"id\":\"1-B\""));
        assertTrue(fresh.contains("\"id\":\"1-B\""));
    }
}
