package finos.traderx.tradeprocessor.service;

import finos.traderx.tradeprocessor.model.*;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Persisted descriptor agreement; no fallback from current environment or process identity. */
@Component
public final class RunRegistry {
  public static final String UNKNOWN="legacy-unknown";
  private final JdbcTemplate jdbc;
  public RunRegistry(JdbcTemplate jdbc) { this.jdbc=jdbc; }
  public String activeScope() {
    return jdbc.queryForObject("SELECT projection_scope FROM projection_active WHERE singleton_id=1",String.class);
  }
  public Map<String,Object> scope(String scope) {
    if (scope==null || !scope.matches("[a-z0-9_-]{1,64}")) {
      throw new IllegalArgumentException("RUN_SCOPE_INVALID");
    }
    var rows=jdbc.queryForList("SELECT * FROM projection_runs WHERE projection_scope=?",scope);
    if (rows.size()!=1) { throw new IllegalArgumentException("RUN_SCOPE_UNREGISTERED: "+scope); }
    return rows.get(0);
  }
  public void validate(ScopedEvent e) {
    var run=scope(e.getProjectionScope());
    if (UNKNOWN.equals(e.getProjectionScope()) && e.getClusterEpoch()==null
        && e.getEventIdScheme()==null && e.getRunDescriptorHash()==null && e.getConsensusSequence()==null) {
      return; // Existing bytes keep their unknown legacy scope; never attributed to active run.
    }
    if (!Objects.equals(run.get("cluster_epoch"),e.getClusterEpoch())
        || !Objects.equals(run.get("event_id_scheme"),e.getEventIdScheme())
        || !Objects.equals(run.get("descriptor_hash"),e.getRunDescriptorHash())
        || e.getRunDescriptorHash()==null || !e.getRunDescriptorHash().matches("[0-9a-f]{64}")
        || e.getConsensusSequence()==null || e.getConsensusSequence()<=0) {
      throw new IllegalArgumentException("RUN_DESCRIPTOR_MISMATCH: "+e.getProjectionScope());
    }
    if (!"epoch-v1".equals(e.getEventIdScheme()) || e.getClusterEpoch()==null
        || !e.getClusterEpoch().matches("[a-z0-9_]{1,25}")) {
      throw new IllegalArgumentException("RUN_ID_SCHEME_INVALID: managed event requires epoch-v1");
    }
    if (e instanceof TradeOrder t) {
      String prefix="e1-"+e.getClusterEpoch()+"-";
      String side=t.getSide()==TradeSide.Buy ? "-B" : "-S";
      String id=t.getId();
      if (id==null || id.length()>50 || !id.startsWith(prefix) || !id.endsWith(side)) {
        throw new IllegalArgumentException("RUN_TRADE_ID_MISMATCH");
      }
      positive(id.substring(prefix.length(),id.length()-2),Long.MAX_VALUE);
      orderId(t.getSourceOrderId(),e.getClusterEpoch());
    } else if (e instanceof OrderUpdate o) {
      orderId(o.getId(),e.getClusterEpoch());
      if (o.getOutputOrdinal()==null || o.getOutputOrdinal()<0
          || o.getCreatedAt()==null || o.getUpdatedAt()==null) {
        throw new IllegalArgumentException("RUN_ORDER_WITNESS_MISSING");
      }
    }
  }
  private static void positive(String value,long max) {
    if (!value.matches("[1-9][0-9]*")) { throw new IllegalArgumentException("RUN_ID_INVALID"); }
    try { if (Long.parseLong(value)>max) {throw new NumberFormatException();} }
    catch (NumberFormatException ex) {throw new IllegalArgumentException("RUN_ID_OVERFLOW",ex);}
  }
  private static void orderId(String id,String epoch) {
    String prefix=epoch+"-";
    if (id==null || id.length()>50 || !id.startsWith(prefix)) {
      throw new IllegalArgumentException("RUN_ORDER_ID_MISMATCH");
    }
    positive(id.substring(prefix.length()),Integer.MAX_VALUE);
  }
  public void requireWritable(ScopedEvent event) {
    Object phase=scope(event.getProjectionScope()).get("phase");
    if ("DRAINING".equals(phase)) {
      Object frozen=scope(event.getProjectionScope()).get("frozen_seq");
      if (UNKNOWN.equals(event.getProjectionScope()) || (event.getConsensusSequence()!=null
          && frozen instanceof Number && event.getConsensusSequence()<=((Number)frozen).longValue())) return;
    }
    if (!"ACTIVE".equals(phase) && !"LEGACY".equals(phase)) {
      throw new IllegalStateException("RUN_SCOPE_NOT_WRITABLE: "+event.getProjectionScope()+" phase="+phase);
    }
  }
  public void applied(ScopedEvent e,boolean trade) {
    if (e.getConsensusSequence()==null) { return; }
    String field=trade ? "trade_rows" : "order_updates";
    jdbc.update("UPDATE projection_runs SET "+field+"="+field+"+1, checkpoint_seq=GREATEST(checkpoint_seq,?) WHERE projection_scope=?",
                e.getConsensusSequence(),e.getProjectionScope());
  }
  public long[] recon(String scope) {
    jdbc.update("INSERT IGNORE INTO projection_recon(projection_scope) VALUES (?)",scope);
    return jdbc.queryForObject("SELECT cursor_seq,matched,missing,mismatched FROM projection_recon WHERE projection_scope=?",
      (rs,row)->new long[]{rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4)},scope);
  }
  public void saveRecon(String scope,long cursor,long matched,long missing,long mismatched) {
    jdbc.update("UPDATE projection_recon SET cursor_seq=?,matched=?,missing=?,mismatched=? WHERE projection_scope=? AND cursor_seq<=?",
      cursor,matched,missing,mismatched,scope,cursor);
  }

}
