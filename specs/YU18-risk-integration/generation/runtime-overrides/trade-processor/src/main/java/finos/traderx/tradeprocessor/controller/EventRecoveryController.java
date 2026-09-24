package finos.traderx.tradeprocessor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import finos.traderx.tradeprocessor.service.EventRecoveryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Local operator repair; never changes run selection or admission. */
@RestController
@RequestMapping("/v2/projection-recovery")
public final class EventRecoveryController {
    private final EventRecoveryService recovery;
    private final String token;
    public EventRecoveryController(EventRecoveryService recovery,@Value("${RISK_CONTROL_TOKEN:dev-risk-control}") String token) {
        this.recovery=recovery;this.token=token;
    }
    @PostMapping("/catch-up")
    public Object recover(@RequestBody JsonNode body,
            @RequestHeader(value="X-Risk-Control-Token",required=false) String provided,
            @RequestHeader(value="X-Risk-Operator",required=false) String operator) {
        if(!token.equals(provided) || operator==null || operator.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"risk-control credentials required");
        if(!body.path("projectionScope").isTextual() || !body.path("endpoint").isTextual())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"projectionScope and endpoint required");
        try {return recovery.catchUp(body.path("projectionScope").asText(),body.path("endpoint").asText());}
        catch(IllegalStateException | IllegalArgumentException ex) {throw new ResponseStatusException(HttpStatus.CONFLICT,ex.getMessage());}
    }
}
