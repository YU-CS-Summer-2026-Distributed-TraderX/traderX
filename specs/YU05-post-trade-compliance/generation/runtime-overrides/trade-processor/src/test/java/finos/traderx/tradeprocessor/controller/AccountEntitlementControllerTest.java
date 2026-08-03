package finos.traderx.tradeprocessor.controller;

import finos.traderx.tradeprocessor.auth.JwtTokenMinter;
import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import finos.traderx.tradeprocessor.service.SettlementService;
import finos.traderx.tradeprocessor.service.TcaService;
import finos.traderx.tradeprocessor.service.ReconciliationService;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Per-trade account entitlement matrix (FR-PTC41, ADR-025). */
class AccountEntitlementControllerTest {
    private static final String SECRET = "controller-test-shared-secret";
    private static final String TRADE_ID = "trd-09b-42";

    private TcaService tcaService;
    private SettlementService settlementService;
    private ReconciliationService reconciliationService;
    private TradeRepository trades;
    private MockMvc mvc;
    private String admin;
    private String ownAccount;
    private String foreignAccount;

    @BeforeEach
    void setUp() throws Exception {
        tcaService = mock(TcaService.class);
        settlementService = mock(SettlementService.class);
        reconciliationService = mock(ReconciliationService.class);
        trades = mock(TradeRepository.class);
        Trade trade = new Trade();
        trade.setId(TRADE_ID);
        trade.setAccountId(22214);
        when(trades.findById(TRADE_ID)).thenReturn(Optional.of(trade));
        when(tcaService.computeForTrade(TRADE_ID)).thenReturn(new TcaService.TcaReport(
            TRADE_ID, "IBM", "Buy", 100, new BigDecimal("100.000"),
            new BigDecimal("99.000"), new BigDecimal("98.000"), new BigDecimal("101.01"), 3));
        when(settlementService.forceSettle(TRADE_ID)).thenReturn(SettlementService.ForceResult.SETTLED);
        when(reconciliationService.runOrphanSweep()).thenReturn(
            new ReconciliationService.OrphanSweepResult(java.time.Instant.EPOCH, 2, 2, 0, java.util.List.of()));
        when(reconciliationService.lastOrphanSweep()).thenReturn(
            new ReconciliationService.OrphanSweepResult(java.time.Instant.EPOCH, 2, 2, 0, java.util.List.of()));

        JwtTokenMinter minter = new JwtTokenMinter(SECRET);
        admin = "Bearer " + minter.mint("admin", Set.of(), true, 0L);
        ownAccount = "Bearer " + minter.mint("owner", Set.of(22214), false, 0L);
        foreignAccount = "Bearer " + minter.mint("other", Set.of(44044), false, 0L);
        mvc = standaloneSetup(
            new TcaController(tcaService, trades, SECRET),
            new SettlementController(settlementService, trades, SECRET),
            new ReconStatusController(reconciliationService, SECRET)).build();
    }

    @Test
    void frPtc41AdminAndOwnerCanReadTca() throws Exception {
        mvc.perform(get("/tca/report/{tradeId}", TRADE_ID).header("Authorization", admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.tradeId").value(TRADE_ID));
        mvc.perform(get("/tca/report/{tradeId}", TRADE_ID).header("Authorization", ownAccount))
            .andExpect(status().isOk()).andExpect(jsonPath("$.tradeId").value(TRADE_ID));
    }

    @Test
    void frPtc41ForeignAccountIs403AndEmptyBearerIs401() throws Exception {
        mvc.perform(get("/tca/report/{tradeId}", TRADE_ID).header("Authorization", foreignAccount))
            .andExpect(status().isForbidden());
        mvc.perform(get("/tca/report/{tradeId}", TRADE_ID).header("Authorization", ""))
            .andExpect(status().isUnauthorized());
        // Physically absent Authorization header (required=false) → 401, not Spring's default 400.
        mvc.perform(get("/tca/report/{tradeId}", TRADE_ID))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(tcaService);
    }

    @Test
    void frPtc41SettlementForceUsesSameEntitlementMatrix() throws Exception {
        mvc.perform(post("/trades/{id}/settlement/force", TRADE_ID).header("Authorization", foreignAccount))
            .andExpect(status().isForbidden());
        mvc.perform(post("/trades/{id}/settlement/force", TRADE_ID).header("Authorization", ownAccount))
            .andExpect(status().isOk());
        mvc.perform(post("/trades/{id}/settlement/force", TRADE_ID).header("Authorization", admin))
            .andExpect(status().isOk());

        verify(settlementService, org.mockito.Mockito.times(2)).forceSettle(TRADE_ID);
    }

    @Test
    void frPtc41OrphanSweepRequiresAdminForCrossAccountTradeIds() throws Exception {
        mvc.perform(post("/recon/orphan-sweep").header("Authorization", ownAccount))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/recon/orphan-sweep/last").header("Authorization", foreignAccount))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/recon/orphan-sweep").header("Authorization", admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.orphanCount").value(0));
        mvc.perform(get("/recon/orphan-sweep/last").header("Authorization", admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.fullHistoryTradeCount").value(2));
    }
}
