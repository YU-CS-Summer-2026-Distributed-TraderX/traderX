package finos.traderx.accountservice.controller;

import finos.traderx.accountservice.model.AccountControlEvent;
import finos.traderx.accountservice.service.AccountControlFeedService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/account/control", produces = "application/json")
public class AccountControlFeedController {
  private final AccountControlFeedService feed;

  public AccountControlFeedController(AccountControlFeedService feed) { this.feed = feed; }

  @GetMapping("/snapshot")
  public AccountControlFeedService.Snapshot snapshot() { return feed.snapshot(); }

  @GetMapping("/deltas")
  public List<AccountControlEvent> deltas(@RequestParam(defaultValue = "0") long after) {
    return feed.deltasAfter(after);
  }
}
