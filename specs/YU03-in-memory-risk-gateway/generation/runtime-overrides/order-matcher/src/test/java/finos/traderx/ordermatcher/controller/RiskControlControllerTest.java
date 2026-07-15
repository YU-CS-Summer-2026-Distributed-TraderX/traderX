package finos.traderx.ordermatcher.controller;

import finos.traderx.ordermatcher.lmax.LmaxEngine;
import finos.traderx.ordermatcher.risk.GatewayReplicaStore;
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

/** HTTP contract for the two-tier risk control boundary (FR-IMRG30/31 and FR-IMRG24). */
class RiskControlControllerTest {
    private static final String TOKEN = "test-risk-token";

    private GatewayReplicaStore replicas;
    private LmaxEngine engine;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        replicas = new GatewayReplicaStore("22214", "IBM,MSFT", 10_000,
            1_000_000_000_000L, 30_000L, 5_000L, 64, 64);
        replicas.seed();
        replicas.alignSecurityIds(ticker -> "IBM".equals(ticker) ? 0 : 1);
        replicas.markReady();
        engine = mock(LmaxEngine.class);
        mvc = standaloneSetup(new RiskControlController(replicas, engine, TOKEN)).build();
    }

    @Test
    void frImrg30InvalidTokenOrBlankOperatorReturns401WithoutMutation() throws Exception {
        mvc.perform(post("/risk/control/account")
                .header("X-Risk-Control-Token", "wrong")
                .header("X-Risk-Operator", "operator")
                .contentType("application/json")
                .content("{\"accountId\":22214,\"enabled\":false}"))
            .andExpect(status().isUnauthorized());

        mvc.perform(post("/risk/control/account")
                .header("X-Risk-Control-Token", TOKEN)
                .header("X-Risk-Operator", " ")
                .contentType("application/json")
                .content("{\"accountId\":22214,\"enabled\":false}"))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(engine);
    }

    @Test
    void frImrg31AccountMutationUpdatesReplicaAndSequencedAuthority() throws Exception {
        long expectedVersion = replicas.sourceVersion() + 1;

        mvc.perform(post("/risk/control/account")
                .header("X-Risk-Control-Token", TOKEN)
                .header("X-Risk-Operator", "risk-operator")
                .contentType("application/json")
                .content("{\"accountId\":22214,\"enabled\":false}"))
            .andExpect(status().isNoContent());

        verify(engine).submitAccountControl(22214, false, expectedVersion);
        org.junit.jupiter.api.Assertions.assertEquals(
            finos.traderx.ordermatcher.risk.RiskReason.ACCOUNT_DISABLED,
            replicas.screen(22214, "IBM", 10, new java.math.BigDecimal("100"), false, 1_000L));
    }

    @Test
    void frImrg24RestrictionUpdatesBothTiersAndSequencesRestingOrderCancels() throws Exception {
        when(engine.cancelOpenOrdersForSecurity("IBM")).thenReturn(2);
        long expectedVersion = replicas.sourceVersion() + 1;

        mvc.perform(post("/risk/control/restriction")
                .header("X-Risk-Control-Token", TOKEN)
                .header("X-Risk-Operator", "risk-operator")
                .contentType("application/json")
                .content("{\"ticker\":\"IBM\",\"restricted\":true}"))
            .andExpect(status().isNoContent());

        verify(engine).submitRestrictionControl("IBM", true, expectedVersion);
        verify(engine).cancelOpenOrdersForSecurity("IBM");
        org.junit.jupiter.api.Assertions.assertEquals(
            finos.traderx.ordermatcher.risk.RiskReason.RESTRICTED,
            replicas.screen(22214, "IBM", 10, new java.math.BigDecimal("100"), false, 1_000L));
    }

    @Test
    void frImrg31SnapshotHasStableControlStateShape() throws Exception {
        mvc.perform(get("/risk/control/snapshot"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sourceEpoch").value(1))
            .andExpect(jsonPath("$.watermark").isNumber())
            .andExpect(jsonPath("$.highWatermark").isNumber())
            .andExpect(jsonPath("$.policyVersion").value(1))
            .andExpect(jsonPath("$.ready").value(true))
            .andExpect(jsonPath("$.accounts.length()").value(1))
            .andExpect(jsonPath("$.securities.length()").value(2));
    }
}
