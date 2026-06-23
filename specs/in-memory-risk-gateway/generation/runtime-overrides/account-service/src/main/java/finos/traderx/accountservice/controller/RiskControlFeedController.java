package finos.traderx.accountservice.controller;

import finos.traderx.accountservice.model.RiskControlEvent;
import finos.traderx.accountservice.service.RiskControlFeedService;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated authoritative risk-control owner. */
@RestController
@RequestMapping(value = "/risk-admin/control", produces = "application/json")
public final class RiskControlFeedController {
  private final RiskControlFeedService feed;
  private final String token;

  public RiskControlFeedController(RiskControlFeedService feed,
      @Value("${risk.control.token:dev-risk-control}") String token) {
    this.feed = feed;
    this.token = token;
  }

  @PostMapping
  public ResponseEntity<RiskControlEvent> mutate(
      @RequestHeader("X-Risk-Control-Token") String suppliedToken,
      @RequestHeader("X-Risk-Operator") String operator,
      @RequestBody RiskControlFeedService.Mutation mutation) {
    authorize(suppliedToken, operator);
    return ResponseEntity.status(HttpStatus.CREATED).body(feed.mutate(mutation, operator.trim()));
  }

  @GetMapping("/snapshot")
  public RiskControlFeedService.Snapshot snapshot() { return feed.snapshot(); }

  @GetMapping("/deltas")
  public List<RiskControlEvent> deltas(@RequestParam(defaultValue = "0") long after) {
    return feed.deltasAfter(after);
  }

  @ExceptionHandler(RiskControlFeedService.StaleControlVersionException.class)
  ResponseEntity<String> stale(RiskControlFeedService.StaleControlVersionException failure) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(failure.getMessage());
  }

  private void authorize(String suppliedToken, String operator) {
    if (!token.equals(suppliedToken) || operator == null || operator.isBlank()) {
      throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED,
          "invalid risk-control credentials");
    }
  }
}
