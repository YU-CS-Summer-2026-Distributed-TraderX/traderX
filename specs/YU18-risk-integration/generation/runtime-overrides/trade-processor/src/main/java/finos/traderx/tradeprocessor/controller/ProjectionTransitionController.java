package finos.traderx.tradeprocessor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import finos.traderx.tradeprocessor.service.ProjectionTransitionService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Operator maintenance API; ordinary consumers cannot select or relabel a run. */
@RestController
@RequestMapping("/v2/projection-control")
public final class ProjectionTransitionController {
    private final ProjectionTransitionService transitions;private final String token;
    public ProjectionTransitionController(ProjectionTransitionService transitions,@Value("${RISK_CONTROL_TOKEN:dev-risk-control}") String token) {
        this.transitions=transitions;this.token=token;
    }
    private void authorize(String provided,String operator) {
        if(!token.equals(provided) || operator==null || operator.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"risk-control credentials required");
    }
    private static String text(JsonNode body,String field) {
        if(!body.path(field).isTextual() || body.path(field).asText().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"missing "+field);
        return body.path(field).asText();
    }
    private static Map<String,Object> summary(Map<String,Object> result) {
        var copy=new java.util.LinkedHashMap<>(result);copy.remove("witness_json");copy.remove("descriptor_json");return copy;
    }
    @GetMapping("/state/{id}")
    public Object state(@PathVariable String id,@RequestHeader(value="X-Risk-Control-Token",required=false) String token,
                        @RequestHeader(value="X-Risk-Operator",required=false) String operator) {
        authorize(token,operator);
        try{return summary(transitions.state(id));}
        catch(IllegalStateException ex){throw new ResponseStatusException(HttpStatus.NOT_FOUND,ex.getMessage());}
    }
    @GetMapping("/activation/{hash}")
    public Object activation(@PathVariable String hash,@RequestHeader(value="X-Risk-Control-Token",required=false) String token,
                             @RequestHeader(value="X-Risk-Operator",required=false) String operator) {
        authorize(token,operator);
        try{return transitions.activationReady(hash);}
        catch(IllegalStateException ex){throw new ResponseStatusException(HttpStatus.CONFLICT,ex.getMessage());}
    }
    @PostMapping("/{action}")
    public Object apply(@PathVariable String action,@RequestBody JsonNode body,
                        @RequestHeader(value="X-Risk-Control-Token",required=false) String token,
                        @RequestHeader(value="X-Risk-Operator",required=false) String operator) {
        authorize(token,operator);
        try {
            return summary(switch(action) {
                case "adopt-legacy" -> transitions.adoptLegacy(text(body,"descriptorJson"),text(body,"oldEndpoint"));
                case "prepare" -> transitions.prepare(text(body,"transitionId"),text(body,"oldScope"),text(body,"descriptorJson"),text(body,"newEndpoint"));
                case "freeze" -> transitions.freeze(text(body,"transitionId"),text(body,"oldEndpoint"));
                case "verify" -> transitions.verify(text(body,"transitionId"),text(body,"oldEndpoint"),text(body,"newEndpoint"));
                case "select" -> transitions.select(text(body,"transitionId"),text(body,"oldEndpoint"),text(body,"newEndpoint"));
                case "activate" -> transitions.activate(text(body,"transitionId"),text(body,"newEndpoint"));
                default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND,"unknown migration action");
            });
        } catch(IllegalArgumentException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,ex.getMessage());
        }
    }
}
