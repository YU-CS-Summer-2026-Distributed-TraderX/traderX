package finos.traderx.ordermatcher.controller;

import finos.traderx.ordermatcher.risk.RiskReason;
import finos.traderx.ordermatcher.risk.RiskRejectedException;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Stable Gateway/BLP rejection HTTP body contract (FR-IMRG12/19). */
class RiskExceptionHandlerTest {
    @RestController
    static final class RejectingController {
        @GetMapping("/test/reject")
        void reject(@RequestParam RiskReason reason) {
            throw new RiskRejectedException("client-7", reason, 42L, 99L);
        }
    }

    @Test
    void frImrg12StableReasonsMapTo422Bodies() throws Exception {
        var mvc = standaloneSetup(new RejectingController())
            .setControllerAdvice(new RiskExceptionHandler()).build();

        for (RiskReason reason : new RiskReason[] { RiskReason.UNKNOWN_ACCOUNT,
                RiskReason.PRICE_COLLAR, RiskReason.ORDER_SIZE, RiskReason.RESTRICTED,
                RiskReason.KILL_SWITCH }) {
            mvc.perform(get("/test/reject").param("reason", reason.name()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.clientOrderId").value("client-7"))
                .andExpect(jsonPath("$.decision").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value(reason.name()))
                .andExpect(jsonPath("$.policyVersion").value(42))
                .andExpect(jsonPath("$.commandSequence").value(99));
        }
    }

    @Test
    void frImrg05StaleControlStateMapsTo503() throws Exception {
        var mvc = standaloneSetup(new RejectingController())
            .setControllerAdvice(new RiskExceptionHandler()).build();

        mvc.perform(get("/test/reject").param("reason", RiskReason.CONTROL_STATE_STALE.name()))
            .andExpect(status().isServiceUnavailable());
    }
}
