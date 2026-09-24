package finos.traderx.positionservice.controller;
import finos.traderx.positionservice.repository.*;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Explicit historical namespace; legacy routes read only the atomically selected active scope. */
@RestController
@RequestMapping("/v2/projections")
public final class ScopedProjectionController {
 private final PositionRepository positions; private final TradeRepository trades;
 private final JdbcTemplate jdbc;
 public ScopedProjectionController(PositionRepository positions,TradeRepository trades,JdbcTemplate jdbc) {
  this.positions=positions;this.trades=trades;this.jdbc=jdbc;
 }
 @GetMapping public List<Map<String,Object>> scopes() {
  return jdbc.queryForList("SELECT projection_scope,cluster_epoch,event_id_scheme,descriptor_hash,phase,checkpoint_seq FROM projection_runs ORDER BY projection_scope");
 }
 private void requireScope(String scope) {
  Integer count=jdbc.queryForObject("SELECT count(*) FROM projection_runs WHERE projection_scope=?",Integer.class,scope);
  if (count==null || count!=1) {throw new ResponseStatusException(HttpStatus.NOT_FOUND,"unknown projection scope");}
 }
 @GetMapping("/{scope}/accounts/{account}/positions")
 public Object positions(@PathVariable String scope,@PathVariable int account) {
  requireScope(scope);return positions.findByScopeAndAccount(scope,account);
 }
 @GetMapping("/{scope}/accounts/{account}/trades")
 public Object trades(@PathVariable String scope,@PathVariable int account) {
  requireScope(scope);return trades.findByScopeAndAccount(scope,account);
 }
}
