package finos.traderx.ordermatcher.controller;

import finos.traderx.ordermatcher.auth.JwtTokenMinter;
import finos.traderx.ordermatcher.lmax.LmaxEngine;
import finos.traderx.ordermatcher.lmax.TradeBlotter;
import java.util.List;
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

/** Cross-account compliance entitlement matrix (FR-PTC41, ADR-025). */
class ComplianceAdminControllerTest {
    private static final String SECRET = "controller-test-shared-secret";

    private LmaxEngine engine;
    private TradeBlotter blotter;
    private MockMvc mvc;
    private String admin;
    private String accountScoped;

    @BeforeEach
    void setUp() throws Exception {
        engine = mock(LmaxEngine.class);
        blotter = new TradeBlotter(100);
        when(engine.generateRegulatoryReport(0L, 0L)).thenReturn(List.of());
        when(engine.reindexFullHistory()).thenReturn(blotter);
        JwtTokenMinter minter = new JwtTokenMinter(SECRET);
        admin = "Bearer " + minter.mint("admin", Set.of(), true, 0L);
        accountScoped = "Bearer " + minter.mint("trader", Set.of(22214), false, 0L);
        mvc = standaloneSetup(
            new RegulatoryReportController(engine, SECRET),
            new ReconController(blotter, engine, SECRET, 100)).build();
    }

    @Test
    void frPtc41AdminCanUseCrossAccountEndpoints() throws Exception {
        mvc.perform(get("/regulatory/report").header("Authorization", admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(get("/recon/trades/blotter").header("Authorization", admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(post("/recon/full-history/reindex").header("Authorization", admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.indexedTrades").value(0));

        verify(engine).generateRegulatoryReport(0L, 0L);
        verify(engine).reindexFullHistory();
    }

    @Test
    void frPtc41AccountScopedJwtIs401OnCrossAccountEndpoints() throws Exception {
        mvc.perform(get("/regulatory/report").header("Authorization", accountScoped))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/recon/trades/blotter").header("Authorization", accountScoped))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/recon/full-history/reindex").header("Authorization", accountScoped))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(engine);
    }

    @Test
    void frPtc41EmptyBearerIs401() throws Exception {
        mvc.perform(get("/regulatory/report").header("Authorization", ""))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/recon/trades/blotter").header("Authorization", ""))
            .andExpect(status().isUnauthorized());
    }
}
