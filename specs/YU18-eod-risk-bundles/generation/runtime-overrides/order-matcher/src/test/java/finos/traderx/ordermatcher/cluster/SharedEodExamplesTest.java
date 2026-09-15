package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Real sequenced service + production exporters in-process. No Aeron cluster or EOD services. */
class SharedEodExamplesTest {
    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[1024]);
    private long timestamp = 1_000_000_000_000L;
    private final LocalDate date = LocalDate.of(2025, 6, 2);
    @TempDir Path temporary;

    @Test void bill() throws Exception { exportCase("bill"); }
    @Test void noteLongAndShort() throws Exception { exportCase("note"); }
    @Test void currentSofrConvention() throws Exception { exportCase("sofr"); }

    private void exportCase(String example) throws Exception {
        String requested = System.getenv("TRADERX_EOD_EXAMPLES_OUTPUT");
        Path root = requested == null ? temporary : Path.of(requested).resolve(example);
        Files.createDirectories(root);
        MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        service.initEngine();
        for (int account : new int[]{22214, 42422}) {
            InputEvent control = new InputEvent();
            control.type = InputEvent.TYPE_ACCOUNT_CONTROL;
            control.accountId = account;
            control.setControlEnabled(true); control.setControlVersion(1);
            apply(service, control);
        }
        String[] names = example.equals("sofr") ? new String[]{} : new String[]{
            example.equals("bill") ? "UST-BILL-20251202" : "UST-NOTE-20261215"};
        for (String ticker : names) {
            codec.encodeSymbolRegister(buffer, 0, ++timestamp, ticker);
            service.onSessionMessage(null, timestamp, buffer, 0, AeronReplicationCodec.SYMBOL_BYTES, null);
            int security = service.symbolIdFor(ticker);
            assertTrue(security >= 0);
            InputEvent control = new InputEvent();
            control.type = InputEvent.TYPE_SECURITY_CONTROL; control.securityId = security;
            control.setControlEnabled(true); control.setControlVersion(1); apply(service, control);
            long price = example.equals("bill") ? 985_000 : 1_015_000;
            InputEvent tick = new InputEvent();
            tick.type = InputEvent.TYPE_PRICE_TICK; tick.securityId = security; tick.priceTicks = price;
            apply(service, tick);
            for (byte side : new byte[]{InputEvent.SIDE_SELL, InputEvent.SIDE_BUY}) {
                InputEvent order = new InputEvent(); order.type = InputEvent.TYPE_ORDER_NEW;
                order.accountId = side == InputEvent.SIDE_BUY ? 22214 : 42422;
                order.securityId = security; order.side = side;
                order.qty = 100_000; order.limitPx = price;
                apply(service, order);
            }
        }
        assertEquals(names.length * 2, service.engine().tradeCounter(), "each cross books two account trade legs");
        assertEquals(names.length * 2, service.engine().positionTuples().size(), "signed buyer/seller positions");
        if (example.equals("sofr")) {
        InputEvent swap = new InputEvent(); swap.type = InputEvent.TYPE_SWAP_BOOK;
        swap.accountId = 22214; swap.side = InputEvent.SWAP_PAY_FIXED;
        swap.qty = 1_000_000; swap.limitPx = 40_000; swap.securityId = 0;
        swap.setClientOrderKey(123); swap.setSwapDates((int)date.plusDays(1).toEpochDay(),
            (int)date.plusDays(1).plusYears(5).toEpochDay());
        apply(service, swap);
        }
        for (long[] position : service.engine().positionTuples()) {
            long expected = 100_000;
            assertEquals(position[0] == 22214 ? expected : -expected, position[2]);
        }
        assertEquals(example.equals("sofr") ? 1 : 0, service.contractCount(), "SOFR booking enters contract store");
        marker(service);
        long seq = service.lastExtractCutSeq();
        String[] tickers = new String[MatchingEngineClusteredService.MAX_SECURITIES];
        for (String ticker : names) tickers[service.symbolIdFor(ticker)] = ticker;
        String cut = RiskExtractCut.render(seq, date.toEpochDay(), 1, service.engine().positionTuples(),
            service.engine().priceTuples(), tickers, id -> service.risk().contractMultiplier(id), service.contractTuples());
        assertEquals(service.lastExtractCutSha(), RiskExtractCut.sha256(cut));
        marker(service);
        assertEquals(seq + 1, service.lastExtractCutSeq());
        var stamp = new RiskExtractCsv.Stamp(seq, date, 1, RiskExtractCut.sha256(cut));
        var accounts = Map.of(22214, new RiskExtractCsv.Counterparty("SYNTH-BUY", "SYNTH-NS-B", "USD"),
                              42422, new RiskExtractCsv.Counterparty("SYNTH-SELL", "SYNTH-NS-S", "USD"));
        String positions = RiskExtractCsv.render(cut,
            Map.of("UST-NOTE-20261215", new RiskExtractCsv.Mark(new BigDecimal("1.015"), "SYNTHETIC"),
                   "UST-BILL-20251202", new RiskExtractCsv.Mark(new BigDecimal("0.985"), "SYNTHETIC")),
            accounts, Map.of("UST-BILL-20251202", RiskExtractCsv.BondStatic.treasury("0", "2025-12-02"),
                             "UST-NOTE-20261215", RiskExtractCsv.BondStatic.treasury("4.0", "2026-12-15")), stamp);
        String contracts = SwapContractCsv.render(cut, accounts, stamp);
        assertEquals(names.length * 2, positions.lines().filter(l -> !l.startsWith("#")).count() - 1);
        if (example.equals("sofr")) {
            assertTrue(contracts.contains(",PAY_FIXED,1000000,0.040000,USD-SOFR,"));
            assertTrue(contracts.contains(",1Y,ACT/360,USD,"));
        }
        Path pos = root.resolve("positions.csv"), otc = root.resolve("contracts.csv");
        Files.writeString(root.resolve("cut.txt"), cut, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
        Files.writeString(pos, positions, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
        Files.writeString(otc, contracts, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
        String ready = RiskExtractReady.payload(stamp, seq + 1, positions, contracts,
            pos.toUri().toString(), otc.toUri().toString());
        Path receipt = RiskExtractReady.publish(root.resolve("receipts"), stamp, ready);
        assertEquals(receipt, RiskExtractReady.publish(root.resolve("receipts"), stamp, ready));
        assertThrows(java.io.IOException.class, () -> RiskExtractReady.publish(root.resolve("receipts"), stamp, ready + " "));
        assertThrows(IllegalArgumentException.class, () -> RiskExtractReady.payload(stamp, seq + 2,
            positions, contracts, pos.toUri().toString(), otc.toUri().toString()));
    }

    private void marker(MatchingEngineClusteredService service) {
        codec.encodeRiskExtract(buffer, 0, ++timestamp, date.toEpochDay(), 1);
        service.onSessionMessage(null, timestamp, buffer, 0, AeronReplicationCodec.RISK_EXTRACT_BYTES, null);
    }
    private void apply(MatchingEngineClusteredService service, InputEvent event) {
        event.eventTimeMillis = ++timestamp;
        codec.encodeInput(buffer, 0, event, 0, 0, 0);
        service.onSessionMessage(null, timestamp, buffer, 0, AeronReplicationCodec.INPUT_BYTES, null);
    }
}
