package finos.traderx.tradeprocessor.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import finos.traderx.tradeprocessor.OrderFeedHandler;
import finos.traderx.tradeprocessor.model.*;
import finos.traderx.tradeprocessor.repository.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable local maintenance workflow. Every side effect is retryable against immutable intent. */
@Service
public final class ProjectionTransitionService {
    private final JdbcTemplate jdbc;private final ProjectionWriteLock lock;private final RunRegistry registry;
    private final TradeRepository trades;private final OrderRepository orders;private final PositionRepository positions;
    private final RunPeerClient peer;private final TransactionTemplate tx;
    private final ObjectMapper mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    public ProjectionTransitionService(JdbcTemplate jdbc,ProjectionWriteLock lock,RunRegistry registry,
        TradeRepository trades,OrderRepository orders,PositionRepository positions,RunPeerClient peer,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.lock=lock;this.registry=registry;this.trades=trades;this.orders=orders;this.positions=positions;
        this.peer=peer;this.tx=new TransactionTemplate(manager);
    }
    private <T> T locked(Supplier<T> action) {return tx.execute(status->{lock.acquire();return action.get();});}
    private static void require(boolean valid,String reason) {if(!valid) throw new IllegalStateException(reason);}
    private static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
    private JsonNode descriptor(String raw,boolean legacy) {
        try {
            require(raw.getBytes(StandardCharsets.UTF_8).length<=16384,"RUN_DESCRIPTOR_TOO_LARGE");
            JsonNode d=mapper.readTree(raw);Set<String> fields=new HashSet<>();d.fieldNames().forEachRemaining(fields::add);
            require(fields.equals(Set.of("schema","epoch","eventIdScheme","storageLineage","projectionScope","adoptionEvidenceSha256"))
                && "traderx.run.v1".equals(d.path("schema").asText()),"RUN_DESCRIPTOR_SCHEMA");
            require(d.path("epoch").isTextual() && !d.path("epoch").asText().isBlank()
                && d.path("storageLineage").asText().matches("[a-z0-9_-]{1,64}")
                && d.path("projectionScope").asText().matches("[a-z0-9_-]{1,64}"),"RUN_DESCRIPTOR_IDENTITY");
            if(legacy) require("legacy-v0".equals(d.path("eventIdScheme").asText())
                && RunRegistry.UNKNOWN.equals(d.path("projectionScope").asText())
                && d.path("adoptionEvidenceSha256").asText().matches("[0-9a-f]{64}"),"RUN_LEGACY_EVIDENCE_REQUIRED");
            else require("epoch-v1".equals(d.path("eventIdScheme").asText()) && d.path("epoch").asText().matches("[a-z0-9_]{1,25}")
                && !RunRegistry.UNKNOWN.equals(d.path("projectionScope").asText()) && d.path("adoptionEvidenceSha256").isNull(),"RUN_FRESH_DESCRIPTOR_INVALID");
            return d;
        } catch(java.io.IOException ex){throw new IllegalArgumentException("RUN_DESCRIPTOR_JSON",ex);}
    }
    private JsonNode agreed(String endpoint,String scope,String hash,int... phases) {
        JsonNode result=peer.status(endpoint);
        require(hash!=null && hash.equals(result.path("descriptorHash").asText())
            && scope.equals(result.path("projectionScope").asText()),"RUN_CONSUMER_DESCRIPTOR_DISAGREEMENT");
        require(Arrays.stream(phases).anyMatch(p->p==result.path("runPhase").asInt(-1)),"RUN_ADMISSION_PHASE_DISAGREEMENT");
        require(result.path("members").isArray() && !result.path("members").isEmpty(),"RUN_MEMBER_WITNESS_EMPTY");
        for(JsonNode member:result.path("members")) require(member.path("started").asBoolean()
            && member.path("runProtocol").asInt()==1 && hash.equals(member.path("runDescriptorHash").asText())
            && scope.equals(member.path("projectionScope").asText()) && member.path("runPhase").asInt(-1)==result.path("runPhase").asInt(-1),
            "RUN_MEMBER_DESCRIPTOR_DISAGREEMENT");
        return result;
    }
    public Map<String,Object> state(String id) {
        require(id!=null && id.matches("[a-z0-9_-]{1,64}"),"RUN_TRANSITION_ID_INVALID");
        var rows=jdbc.queryForList("SELECT * FROM projection_transitions WHERE transition_id=?",id);
        require(rows.size()==1,"RUN_TRANSITION_UNKNOWN");return rows.get(0);
    }
    public Map<String,Object> activationReady(String hash) {
        require(hash!=null && hash.matches("[0-9a-f]{64}"),"RUN_DESCRIPTOR_HASH_INVALID");
        var rows=jdbc.queryForList("SELECT t.new_scope,t.descriptor_hash,t.witness_hash,t.phase FROM projection_transitions t "
            +"JOIN projection_active a ON a.singleton_id=1 AND a.projection_scope=t.new_scope "
            +"JOIN projection_runs n ON n.projection_scope=t.new_scope AND n.phase='ACTIVE' AND n.descriptor_hash=t.descriptor_hash "
            +"JOIN projection_runs o ON o.projection_scope=t.old_scope AND o.phase='SEALED' "
            +"WHERE t.descriptor_hash=? AND t.phase IN ('SELECTED','COMPLETE') AND t.witness_hash IS NOT NULL",hash);
        require(rows.size()==1,"RUN_ACTIVATION_NOT_VERIFIED");return rows.get(0);
    }
    public Map<String,Object> adoptLegacy(String raw,String endpoint) {
        JsonNode d=descriptor(raw,true);String hash=hash(raw.getBytes(StandardCharsets.UTF_8));
        agreed(endpoint,RunRegistry.UNKNOWN,hash,2,3);
        return locked(()->{
            requireOrderNamespaceCollation();
            var old=registry.scope(RunRegistry.UNKNOWN);
            require(old.get("descriptor_hash")==null || hash.equals(old.get("descriptor_hash")),"RUN_LEGACY_ALREADY_BOUND");
            jdbc.update("UPDATE projection_runs SET cluster_epoch=?,descriptor_hash=?,descriptor_json=?,storage_lineage=? WHERE projection_scope=?",
                d.path("epoch").asText(),hash,raw,d.path("storageLineage").asText(),RunRegistry.UNKNOWN);
            return registry.scope(RunRegistry.UNKNOWN);
        });
    }
    private void requireOrderNamespaceCollation() {
        var collations=jdbc.queryForList("SELECT DISTINCT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
            +"AND ((LOWER(TABLE_NAME)='projection_runs' AND LOWER(COLUMN_NAME)='cluster_epoch') "
            +"OR (LOWER(TABLE_NAME)='orderbook' AND LOWER(COLUMN_NAME)='orderid') "
            +"OR (LOWER(TABLE_NAME)='trades' AND LOWER(COLUMN_NAME)='sourceorderid'))",String.class);
        require(collations.size()==1 && collations.get(0)!=null,"RUN_ORDER_NAMESPACE_COLLATION_MISMATCH");
    }
    private void requireUnusedOrderNamespace(String epoch) {
        requireOrderNamespaceCollation();
        require(jdbc.queryForObject("SELECT count(*) FROM projection_runs WHERE cluster_epoch=?",Integer.class,epoch)==0,
            "RUN_EPOCH_NAMESPACE_ALREADY_RETAINED");
        // Reserve even unattributed historical prefixes, without relabelling those rows.
        require(jdbc.queryForObject("SELECT count(*) FROM orderbook WHERE LEFT(orderid,CHAR_LENGTH(?)+1)=CONCAT(?,'-')",Integer.class,epoch,epoch)==0
            && jdbc.queryForObject("SELECT count(*) FROM trades WHERE LEFT(sourceorderid,CHAR_LENGTH(?)+1)=CONCAT(?,'-')",Integer.class,epoch,epoch)==0,
            "RUN_ORDER_NAMESPACE_ALREADY_RETAINED");
    }
    public Map<String,Object> prepare(String id,String oldScope,String raw,String newEndpoint) {
        require(id!=null && id.matches("[a-z0-9_-]{1,64}"),"RUN_TRANSITION_ID_INVALID");
        JsonNode d=descriptor(raw,false);String scope=d.path("projectionScope").asText(),hash=hash(raw.getBytes(StandardCharsets.UTF_8));
        locked(()->{
            var existing=jdbc.queryForList("SELECT * FROM projection_transitions WHERE transition_id=?",id);
            if(!existing.isEmpty()) {
                require(oldScope.equals(existing.get(0).get("old_scope")) && scope.equals(existing.get(0).get("new_scope"))
                    && hash.equals(existing.get(0).get("descriptor_hash")),"RUN_TRANSITION_INTENT_CONFLICT");return null;
            }
            require(oldScope.equals(registry.activeScope()),"RUN_OLD_SCOPE_NOT_ACTIVE");
            require(registry.scope(oldScope).get("descriptor_hash")!=null,"RUN_OLD_SCOPE_REQUIRES_EXPLICIT_ADOPTION");
            require(jdbc.queryForObject("SELECT count(*) FROM projection_transitions WHERE new_scope=? AND phase<>'COMPLETE'",Integer.class,oldScope)==0,
                "RUN_PREVIOUS_TRANSITION_NOT_COMPLETE");
            requireUnusedOrderNamespace(d.path("epoch").asText());
            jdbc.update("INSERT INTO projection_runs(projection_scope,cluster_epoch,event_id_scheme,descriptor_hash,descriptor_json,storage_lineage,phase) VALUES (?,?,'epoch-v1',?,?,?,'PREPARED')",
                scope,d.path("epoch").asText(),hash,raw,d.path("storageLineage").asText());
            jdbc.update("INSERT INTO projection_transitions(transition_id,old_scope,new_scope,descriptor_hash,phase) VALUES (?,?,?,?,'PREPARED')",id,oldScope,scope,hash);
            return null;
        });
        var result=state(id);
        if("PREPARED".equals(result.get("phase"))) {
            agreed(newEndpoint,scope,hash,0,1);peer.control(newEndpoint,hash,"declare");agreed(newEndpoint,scope,hash,1);
        }
        return state(id);
    }
    public Map<String,Object> freeze(String id,String oldEndpoint) {
        var transition=state(id);String old=(String)transition.get("old_scope");String hash=(String)registry.scope(old).get("descriptor_hash");
        agreed(oldEndpoint,old,hash,2,3);peer.control(oldEndpoint,hash,"freeze");
        JsonNode status=agreed(oldEndpoint,old,hash,3);long boundary=status.path("frozenRunSequence").asLong();require(boundary>0,"RUN_FROZEN_BOUNDARY_MISSING");
        return locked(()->{
            var current=state(id);String phase=(String)current.get("phase");
            if(!"PREPARED".equals(phase)) {require(((Number)current.get("frozen_seq")).longValue()==boundary,"RUN_FROZEN_BOUNDARY_CONFLICT");return current;}
            require(old.equals(registry.activeScope()),"RUN_OLD_SCOPE_NOT_ACTIVE");
            jdbc.update("UPDATE projection_runs SET phase='DRAINING',frozen_seq=? WHERE projection_scope=?",boundary,old);
            jdbc.update("UPDATE projection_transitions SET phase='FROZEN',frozen_seq=? WHERE transition_id=?",boundary,id);return state(id);
        });
    }
    public Map<String,Object> verify(String id,String oldEndpoint,String newEndpoint) {
        var t=state(id);String old=(String)t.get("old_scope"),fresh=(String)t.get("new_scope");
        String hash=(String)registry.scope(old).get("descriptor_hash");agreed(oldEndpoint,old,hash,3);
        agreed(newEndpoint,fresh,(String)t.get("descriptor_hash"),1);
        JsonNode replay=peer.projectionEvents(oldEndpoint);
        require(hash.equals(replay.path("descriptorHash").asText()) && old.equals(replay.path("projectionScope").asText())
            && replay.path("frozenSequence").asLong()==((Number)t.get("frozen_seq")).longValue(),"RUN_REPLAY_BOUNDARY_MISMATCH");
        return locked(()->{
            var current=state(id);
            if("VERIFIED".equals(current.get("phase"))) return current;
            require("FROZEN".equals(current.get("phase")),"RUN_TRANSITION_NOT_FROZEN");
            verifyProjection(old,replay);
            String witness=replay.toString();
            jdbc.update("UPDATE projection_runs SET phase='SEALED' WHERE projection_scope=?",old);
            jdbc.update("UPDATE projection_transitions SET phase='VERIFIED',witness_json=?,witness_hash=? WHERE transition_id=?",
                witness,hash(witness.getBytes(StandardCharsets.UTF_8)),id);return state(id);
        });
    }
    private void verifyProjection(String scope,JsonNode replay) {
        Map<String,TradeOrder> expectedTrades=new TreeMap<>();Map<String,OrderUpdate> expectedOrders=new TreeMap<>();
        Map<String,Long> quantity=new TreeMap<>();
        try {
            for(JsonNode event:replay.path("events")) {
                if("TradeOrder".equals(event.path("type").asText())) {
                    TradeOrder trade=mapper.treeToValue(event.get("payload"),TradeOrder.class);registry.validate(trade);
                    require(scope.equals(trade.getProjectionScope()),"RUN_REPLAY_SCOPE_MISMATCH");
                    require(trade.getConsensusSequence()==null || trade.getConsensusSequence()<=replay.path("frozenSequence").asLong(),"RUN_EVENT_AFTER_FROZEN_BOUNDARY");
                    require(expectedTrades.put(trade.getId(),trade)==null,"RUN_REPLAY_DUPLICATE_TRADE");
                    quantity.merge(trade.getAccountId()+":"+trade.getSecurity(),(long)trade.getQuantity()*(trade.getSide()==TradeSide.Buy?1:-1),Math::addExact);
                } else if("OrderUpdate".equals(event.path("type").asText())) {
                    OrderUpdate order=mapper.treeToValue(event.get("payload"),OrderUpdate.class);registry.validate(order);
                    require(scope.equals(order.getProjectionScope()),"RUN_REPLAY_SCOPE_MISMATCH");
                    require(order.getConsensusSequence()==null || order.getConsensusSequence()<=replay.path("frozenSequence").asLong(),"RUN_EVENT_AFTER_FROZEN_BOUNDARY");
                    expectedOrders.put(order.getId(),order);
                } else throw new IllegalStateException("RUN_REPLAY_EVENT_UNKNOWN");
            }
        } catch(java.io.IOException ex){throw new IllegalArgumentException("RUN_REPLAY_JSON",ex);}
        require(!expectedTrades.isEmpty() && !expectedOrders.isEmpty() && replay.path("replayedMessages").asLong()>0
            && replay.path("shadowTradeCounter").asLong()==expectedTrades.size(),"RUN_REPLAY_NONEMPTY_WITNESS_REQUIRED");
        var localTrades=trades.findByProjectionScope(scope);require(localTrades.size()==expectedTrades.size(),"RUN_TRADE_POPULATION_MISMATCH");
        for(Trade trade:localTrades) {var expected=expectedTrades.get(trade.getId());require(expected!=null,"RUN_ORPHAN_TRADE");TradeService.requireSameTrade(trade,expected);}
        var localOrders=orders.findByProjectionScope(scope);require(localOrders.size()==expectedOrders.size(),"RUN_ORDER_POPULATION_MISMATCH");
        for(OrderRow row:localOrders) {
            var expected=expectedOrders.get(row.getId());require(expected!=null,"RUN_ORPHAN_ORDER");
            if(!RunRegistry.UNKNOWN.equals(scope)) require(OrderProjectionService.digest(expected).equals(row.getEventDigest()),"RUN_ORDER_EVENT_MISMATCH");
            else {
                ObjectNode a=mapper.valueToTree(row),b=mapper.valueToTree(OrderFeedHandler.toRow(expected));
                for(String field:List.of("traceId","createdAt","updatedAt","eventDigest")) {a.remove(field);b.remove(field);}
                require(a.equals(b),"RUN_LEGACY_ORDER_STATE_MISMATCH");
            }
        }
        Map<String,Long> actual=new TreeMap<>();
        for(Position position:positions.findByProjectionScope(scope)) actual.put(position.getAccountId()+":"+position.getSecurity(),position.getQuantity().longValue());
        require(actual.equals(quantity),"RUN_POSITION_QUANTITY_MISMATCH");
    }
    public Map<String,Object> select(String id,String oldEndpoint,String newEndpoint) {
        var t=state(id);String old=(String)t.get("old_scope"),fresh=(String)t.get("new_scope");
        agreed(oldEndpoint,old,(String)registry.scope(old).get("descriptor_hash"),3);
        if(Set.of("SELECTED","COMPLETE").contains(t.get("phase"))) agreed(newEndpoint,fresh,(String)t.get("descriptor_hash"),1,2);
        else agreed(newEndpoint,fresh,(String)t.get("descriptor_hash"),1);
        return locked(()->{
            var current=state(id);
            if(Set.of("SELECTED","COMPLETE").contains(current.get("phase"))) {require(fresh.equals(registry.activeScope()),"RUN_ACTIVE_SCOPE_CHANGED");return current;}
            require("VERIFIED".equals(current.get("phase")) && old.equals(registry.activeScope()),"RUN_VERIFIED_TRANSITION_REQUIRED");
            require(current.get("witness_hash")!=null,"RUN_VERIFICATION_WITNESS_MISSING");
            require(trades.findByProjectionScope(fresh).isEmpty() && orders.findByProjectionScope(fresh).isEmpty()
                && positions.findByProjectionScope(fresh).isEmpty(),"RUN_FRESH_PROJECTION_NOT_EMPTY");
            jdbc.update("UPDATE projection_runs SET phase='ACTIVE' WHERE projection_scope=?",fresh);
            jdbc.update("UPDATE projection_active SET projection_scope=? WHERE singleton_id=1",fresh);
            jdbc.update("UPDATE projection_transitions SET phase='SELECTED' WHERE transition_id=?",id);return state(id);
        });
    }
    public Map<String,Object> activate(String id,String newEndpoint) {
        var t=state(id);String fresh=(String)t.get("new_scope"),hash=(String)t.get("descriptor_hash");
        require(Set.of("SELECTED","COMPLETE").contains(t.get("phase")) && fresh.equals(registry.activeScope()),"RUN_SELECTED_TRANSITION_REQUIRED");
        agreed(newEndpoint,fresh,hash,1,2);peer.control(newEndpoint,hash,"activate");agreed(newEndpoint,fresh,hash,2);
        return locked(()->{
            require(fresh.equals(registry.activeScope()),"RUN_ACTIVE_SCOPE_CHANGED");
            jdbc.update("UPDATE projection_transitions SET phase='COMPLETE' WHERE transition_id=?",id);return state(id);
        });
    }
}
