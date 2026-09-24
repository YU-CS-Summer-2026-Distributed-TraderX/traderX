package finos.traderx.tradeprocessor.service;

import com.fasterxml.jackson.databind.*;
import finos.traderx.tradeprocessor.model.*;
import finos.traderx.tradeprocessor.repository.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit full-prefix repair; a high observed sequence alone never establishes completeness. */
@Service
public final class EventRecoveryService {
    private final JdbcTemplate jdbc;
    private final RunRegistry registry;
    private final ProjectionWriteLock lock;
    private final RunPeerClient peer;
    private final TradeRepository trades;
    private final OrderRepository orders;
    private final PositionRepository positions;
    private final TradeService tradeService;
    private final OrderProjectionService orderService;
    private final TransactionTemplate tx;
    private final int maxEvents;
    private final ObjectMapper mapper=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public EventRecoveryService(JdbcTemplate jdbc,RunRegistry registry,ProjectionWriteLock lock,RunPeerClient peer,
            TradeRepository trades,OrderRepository orders,PositionRepository positions,TradeService tradeService,
            OrderProjectionService orderService,PlatformTransactionManager manager,
            @Value("${recovery.max-events:10000}") int maxEvents) {
        if(maxEvents<1 || maxEvents>100000) throw new IllegalArgumentException("RECOVERY_EVENT_LIMIT_INVALID");
        this.jdbc=jdbc;this.registry=registry;this.lock=lock;this.peer=peer;this.trades=trades;
        this.orders=orders;this.positions=positions;this.tradeService=tradeService;this.orderService=orderService;
        this.tx=new TransactionTemplate(manager);this.maxEvents=maxEvents;
    }
    private static void require(boolean ok,String reason) {if(!ok) throw new IllegalStateException(reason);}
    private Map<String,Object> eligible(String scope) {
        var run=registry.scope(scope);
        require(!RunRegistry.UNKNOWN.equals(scope) && "epoch-v1".equals(run.get("event_id_scheme")),"RECOVERY_EXPLICIT_MANAGED_RUN_REQUIRED");
        require(scope.equals(registry.activeScope()) && Set.of("ACTIVE","DRAINING").contains(run.get("phase")),"RECOVERY_SELECTED_WRITABLE_SCOPE_REQUIRED");
        return run;
    }
    public Map<String,Object> catchUp(String scope,String endpoint) {
        var before=eligible(scope);
        JsonNode source=peer.recoveryEvents(endpoint); // Never accepts caller-provided history.
        return tx.execute(status->{
            lock.acquire();
            var run=eligible(scope);
            require(Objects.equals(before.get("descriptor_hash"),run.get("descriptor_hash")),"RECOVERY_RUN_CHANGED");
            require(Objects.equals(run.get("descriptor_hash"),source.path("descriptorHash").asText())
                && scope.equals(source.path("projectionScope").asText()),"RECOVERY_SOURCE_IDENTITY_MISMATCH");
            long boundary=source.path("completeSequence").asLong(-1);
            require(boundary>0 && boundary==source.path("replayedAppliedSeq").asLong(-2)
                && source.path("replayedMessages").asLong()>=boundary,"RECOVERY_INCOMPLETE_SOURCE");
            if("DRAINING".equals(run.get("phase"))) require(source.path("runPhase").asInt()==3
                && run.get("frozen_seq") instanceof Number
                && source.path("frozenSequence").asLong()==((Number)run.get("frozen_seq")).longValue()
                && boundary>=source.path("frozenSequence").asLong(),"RECOVERY_FROZEN_BOUNDARY_MISMATCH");
            else require(source.path("runPhase").asInt()==2,"RECOVERY_PHASE_CHANGED");
            JsonNode events=source.path("events");
            require(events.isArray() && events.size()<=maxEvents,"RECOVERY_EVENT_LIMIT_OR_SCHEMA");
            Map<String,TradeOrder> expectedTrades=new LinkedHashMap<>();
            Map<String,OrderUpdate> finalOrders=new LinkedHashMap<>();
            Map<String,Map<String,OrderUpdate>> orderHistory=new HashMap<>();
            Set<String> positionKeys=new HashSet<>();
            long sequence=0;Map<Long,Integer> ordinal=new HashMap<>();
            try {
                for(JsonNode event:events) {
                    ScopedEvent parsed;
                    if("TradeOrder".equals(event.path("type").asText())) {
                        TradeOrder t=mapper.treeToValue(event.path("payload"),TradeOrder.class);parsed=t;
                        require(t.getAccountId()!=null && t.getSecurity()!=null && !t.getSecurity().isBlank() && t.getSide()!=null
                            && t.getQuantity()!=null && t.getQuantity()>0 && t.getPrice()!=null && t.getPrice().signum()>0
                            && t.getPrice().scale()<=6,"RECOVERY_TRADE_ECONOMICS_INVALID");
                        require(expectedTrades.put(t.getId(),t)==null,"RECOVERY_DUPLICATE_SOURCE_TRADE");
                        positionKeys.add(t.getAccountId()+":"+t.getSecurity());
                    } else if("OrderUpdate".equals(event.path("type").asText())) {
                        OrderUpdate o=mapper.treeToValue(event.path("payload"),OrderUpdate.class);parsed=o;
                        registry.validate(o);
                        Integer previous=ordinal.put(o.getConsensusSequence(),o.getOutputOrdinal());
                        require(previous==null || o.getOutputOrdinal()>previous,"RECOVERY_ORDER_EVENT_ORDER_INVALID");
                        String key=o.getConsensusSequence()+":"+o.getOutputOrdinal();
                        require(orderHistory.computeIfAbsent(o.getId(),ignored->new HashMap<>()).put(key,o)==null,
                            "RECOVERY_DUPLICATE_SOURCE_ORDER");
                        OrderUpdate previousOrder=finalOrders.put(o.getId(),o);
                        require(previousOrder==null || (Objects.equals(previousOrder.getAccountId(),o.getAccountId())
                            && Objects.equals(previousOrder.getSecurity(),o.getSecurity()) && Objects.equals(previousOrder.getSide(),o.getSide())),
                            "RECOVERY_SOURCE_ORDER_PROVENANCE_CONFLICT: "+o.getId());
                    } else throw new IllegalStateException("RECOVERY_EVENT_TYPE_UNKNOWN");
                    registry.validate(parsed);
                    require(scope.equals(parsed.getProjectionScope()) && parsed.getConsensusSequence()>=sequence
                        && parsed.getConsensusSequence()<=boundary,"RECOVERY_SOURCE_ORDER_OR_SCOPE_INVALID");
                    registry.requireWritable(parsed);
                    sequence=parsed.getConsensusSequence();
                }
            } catch(java.io.IOException ex) {throw new IllegalArgumentException("RECOVERY_EVENT_JSON",ex);}
            require(source.path("shadowTradeCounter").asLong(-1)==expectedTrades.size(),"RECOVERY_TRADE_COUNT_MISMATCH");
            Map<String,Long> retainedQuantity=new HashMap<>();
            for(Trade t:trades.findByProjectionScope(scope)) {
                TradeOrder expected=expectedTrades.get(t.getId());require(expected!=null,"RECOVERY_ORPHAN_OR_AHEAD_TRADE: "+t.getId());
                TradeService.requireSameTrade(t,expected);
                require(t.getState()!=TradeState.Rejected,"RECOVERY_REJECTED_BOOKING_REQUIRES_REVIEW: "+t.getId());
                retainedQuantity.merge(t.getAccountId()+":"+t.getSecurity(),
                    Math.multiplyExact(t.getQuantity().longValue(),t.getSide()==TradeSide.Buy?1L:-1L),Math::addExact);
            }
            for(OrderRow o:orders.findByProjectionScope(scope)) {
                Map<String,OrderUpdate> history=orderHistory.get(o.getId());
                OrderUpdate original=history==null?null:history.get(o.getConsensusSequence()+":"+o.getOutputOrdinal());
                require(original!=null && Objects.equals(o.getEventDigest(),OrderProjectionService.digest(original)),
                    "RECOVERY_ORDER_HISTORY_CONFLICT_OR_AHEAD: "+o.getId());
                var a=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(o);
                var b=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(finos.traderx.tradeprocessor.OrderFeedHandler.toRow(original));
                for(String f:List.of("traceId","eventDigest")) {a.remove(f);b.remove(f);}
                require(a.equals(b),"RECOVERY_RETAINED_ORDER_ROW_CONFLICT: "+o.getId());
            }
            // Missing transport events cannot explain a difference between a retained position
            // and the trades ALREADY booked in the same atomic projection transactions.
            // Signed quantity is independent of delivery order; cost basis is recomputed later.
            Set<String> retainedPositionKeys=new HashSet<>();
            for(Position p:positions.findByProjectionScope(scope)) {
                String key=p.getAccountId()+":"+p.getSecurity();
                require(positionKeys.contains(key),"RECOVERY_POSITION_WITHOUT_SOURCE_TRADES: "+key);
                require(p.getQuantity()!=null && p.getQuantity().longValue()==retainedQuantity.getOrDefault(key,0L),
                    "RECOVERY_RETAINED_POSITION_QUANTITY_CONFLICT: "+key);
                if(!retainedQuantity.containsKey(key)) require(p.getAverageCostBasis()!=null && p.getAverageCostBasis().signum()==0,
                    "RECOVERY_UNATTRIBUTED_POSITION_BASIS: "+key);
                retainedPositionKeys.add(key);
            }
            for(String key:retainedQuantity.keySet()) require(retainedPositionKeys.contains(key),
                "RECOVERY_RETAINED_POSITION_MISSING: "+key);
            for(TradeOrder t:expectedTrades.values()) {
                OrderUpdate order=finalOrders.get(t.getSourceOrderId());
                require(order!=null,"RECOVERY_SOURCE_ORDER_MISSING");
                require(Objects.equals(order.getAccountId(),t.getAccountId()) && Objects.equals(order.getSecurity(),t.getSecurity())
                    && Objects.equals(order.getSide(),t.getSide().name()),"RECOVERY_TRADE_ORDER_PROVENANCE_CONFLICT: "+t.getId());
            }
            var checkpoints=jdbc.queryForList("SELECT * FROM projection_recovery WHERE projection_scope=?",scope);
            require(checkpoints.isEmpty() || ((Number)checkpoints.get(0).get("complete_seq")).longValue()<=boundary,"RECOVERY_BOUNDARY_REWIND");
            require(checkpoints.isEmpty() || Objects.equals(checkpoints.get(0).get("descriptor_hash"),run.get("descriptor_hash")),
                "RECOVERY_CHECKPOINT_IDENTITY_CONFLICT");
            String digest=hash(events.toString());
            if(!checkpoints.isEmpty() && ((Number)checkpoints.get(0).get("complete_seq")).longValue()==boundary)
                require(digest.equals(checkpoints.get(0).get("witness_hash")),"RECOVERY_CHECKPOINT_SOURCE_CONFLICT");
            // Existing service transactions join this one; notifications run only after the outer commit.
            tradeService.processRecoveredTrades(new ArrayList<>(expectedTrades.values()));
            for(OrderUpdate o:finalOrders.values()) orderService.persist(o);
            tradeService.rebuildRecoveredPositions(scope,new ArrayList<>(expectedTrades.values()));
            jdbc.update("INSERT INTO projection_recovery(projection_scope,descriptor_hash,complete_seq,witness_hash,event_count) VALUES (?,?,?,?,?) "
                +"ON DUPLICATE KEY UPDATE complete_seq=VALUES(complete_seq),witness_hash=VALUES(witness_hash),event_count=VALUES(event_count)",
                scope,run.get("descriptor_hash"),boundary,digest,events.size());
            return Map.of("projectionScope",scope,"completeSequence",boundary,"witnessHash",digest,"eventCount",events.size());
        });
    }
    private static String hash(String bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
}
