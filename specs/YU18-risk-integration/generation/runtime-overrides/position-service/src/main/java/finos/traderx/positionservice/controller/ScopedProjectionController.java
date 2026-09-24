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
 @GetMapping("/active") public Map<String,String> active() {
  // One SQL statement binds the pointer to a registered scope; phase is not selection.
  var scopes=jdbc.queryForList("SELECT r.projection_scope FROM projection_active a JOIN projection_runs r "
      +"ON r.projection_scope=a.projection_scope WHERE a.singleton_id=1",String.class);
  if(scopes.size()!=1) {throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"active projection scope unavailable");}
  return Map.of("projectionScope",scopes.get(0));
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
