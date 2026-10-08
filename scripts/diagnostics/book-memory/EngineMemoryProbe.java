package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.RingBuffer;
import com.sun.management.HotSpotDiagnosticMXBean;
import finos.traderx.ordermatcher.risk.BlpRiskState;
import finos.traderx.ordermatcher.risk.RiskMetrics;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import org.agrona.collections.Int2ObjectHashMap;
import sun.misc.Unsafe;

/** Local disposable real-engine fixture; no service threads/network/production configuration. */
public final class EngineMemoryProbe {
    private static Instrumentation instrumentation;
    public static void premain(String unused, Instrumentation agent) { instrumentation = agent; }
    private static long size(Object value) { return instrumentation.getObjectSize(value); }
    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
        return out;
    }
    private static final Map<Class<?>, Set<String>> FIELDS = Map.ofEntries(
        Map.entry(MatchingEngine.class, Set.of("out", "metrics", "risk", "ordersByRef", "booksBySecurity",
            "bookTickPxBySecurity", "lastPxBySecurity", "freeList", "positions", "terminalRing", "pendHead",
            "pendTail", "pendCount", "pegHead", "pegTail", "pegCount", "lastTradePx", "hasTraded",
            "pegRefBid", "pegRefAsk", "triggerQueue", "snapshotTrigger", "stagedExt")),
        Map.entry(LimitBook.class, Set.of("bidHead", "bidTail", "askHead", "askTail", "bidQty", "askQty", "bidBits", "askBits")),
        Map.entry(RestingOrder.class, Set.of("storeNext", "storePrev", "bookNext", "bookPrev", "nextFree")),
        Map.entry(PositionBook.class, Set.of("keys", "values", "avgCostTicks")),
        Map.entry(Int2ObjectHashMap.class, Set.of("keys", "values", "valueCollection", "keySet", "entrySet")),
        Map.entry(BlpRiskState.class, Set.of("accountIds", "accountEnabled", "selfMatchGroups", "reservedNotional",
            "reservedBuyNotional", "reservedSellNotional", "executedNotional", "securityEnabled", "securityRestricted",
            "lastPrice", "lastPriceTime", "contractMultiplier", "idempotencyKeys", "idempotencyOrderRefs",
            "idempotencyDecisions", "idempotencyRetentionKeys", "entitlementKeys", "entitlementEnabled",
            "exposureKeys", "reservedBuyQtyByExposure", "reservedSellQtyByExposure", "metrics")),
        Map.entry(OutputPublisher.class, Set.of("ring", "onBackpressure")),
        Map.entry(OutputEvent.class, Set.of("typed")),
        Map.entry(OutputEvent.TypedShape.class, Set.of()),
        Map.entry(HotPathMetrics.class, Set.of("journalNs", "blpEventNs", "matchNs", "egressNs", "riskDecisionNs",
            "projectorBatchRows", "backpressureWaits")),
        Map.entry(RiskMetrics.class, Set.of("gatewayRejects", "authoritativeDecisions", "duplicates", "gaps", "mismatches",
            "rebootstrap", "controlRejected", "policyVersion", "sourceVersion", "highWatermark", "gatewayValidationNs",
            "sourceWatermarks", "quarantineCounts")));

    private static List<Field> refs(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) fields.add(field);
            }
        }
        fields.sort(Comparator.comparing(f -> f.getDeclaringClass().getName() + "." + f.getName()));
        return fields;
    }
    private static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private static String category(Object object) {
        Class<?> type = object.getClass();
        if (type == MatchingEngine.class) return "engine_object";
        if (type == RestingOrder.class) return "engine_order_entries";
        if (type == LimitBook.class) return "book_objects";
        if (type == PositionBook.class) return "engine_position_object";
        if (type == Int2ObjectHashMap.class) return "engine_index_object";
        if (type == BlpRiskState.class) return "supplied_risk_object";
        if (type == OutputPublisher.class) return "supplied_output_publisher";
        if (type == OutputEvent.class) return "supplied_output_slots";
        if (type == OutputEvent.TypedShape.class) return "supplied_output_typed_shapes";
        if (type == HotPathMetrics.class) return "supplied_hot_metrics_shallow";
        if (type == RiskMetrics.class) return "supplied_risk_metrics_shallow";
        if (type == RingBuffer.class) return "supplied_output_ring_shallow_boundary";
        throw new IllegalStateException("unclassified reachable object: " + type.getName());
    }
    private static String arrayCategory(Class<?> owner) {
        if (owner == MatchingEngine.class) return "engine_arrays";
        if (owner == LimitBook.class) return "book_arrays";
        if (owner == PositionBook.class) return "engine_position_arrays";
        if (owner == Int2ObjectHashMap.class) return "engine_index_arrays";
        if (owner == BlpRiskState.class) return "supplied_risk_arrays";
        throw new IllegalStateException("unclassified array owner " + owner.getName());
    }
    private record Item(Object value, String arrayCategory, String origin) {}

    private static final class Graph {
        final Unsafe unsafe;
        final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        final ArrayDeque<Item> queue = new ArrayDeque<>();
        final Map<String, Map<String, Object>> categories = new TreeMap<>();
        final Map<String, Object> inventories = new TreeMap<>();
        final List<Object> arrays = new ArrayList<>();
        final List<Object> excluded = new ArrayList<>();
        long aliases;
        Graph(Unsafe unsafe) { this.unsafe = unsafe; }
        void root(Object value, String label) { queue.add(new Item(value, null, label)); }
        void measure() throws Exception {
            while (!queue.isEmpty()) {
                Item item = queue.remove(); Object value = item.value;
                if (seen.put(value, true) != null) { aliases++; continue; }
                if (seen.size() > 5000) throw new IllegalStateException("graph object bound exceeded");
                Class<?> type = value.getClass();
                String cat = type.isArray() ? item.arrayCategory : category(value);
                if (cat == null) throw new IllegalStateException("array without explicit owner");
                Map<String, Object> total = categories.computeIfAbsent(cat,
                    unused -> row("object_count", 0L, "shallow_bytes", 0L, "array_payload_bytes", 0L));
                total.put("object_count", (Long) total.get("object_count") + 1);
                total.put("shallow_bytes", (Long) total.get("shallow_bytes") + size(value));
                if (type.isArray()) {
                    int length = Array.getLength(value), scale = unsafe.arrayIndexScale(type), base = unsafe.arrayBaseOffset(type);
                    long payload = (long) length * scale;
                    arrays.add(row("origin", item.origin, "category", cat, "type", type.getName(), "length", length,
                        "element_bytes", scale, "payload_bytes", payload, "base_offset_bytes", base,
                        "alignment_padding_bytes", size(value) - base - payload, "shallow_bytes", size(value)));
                    total.put("array_payload_bytes", (Long) total.get("array_payload_bytes") + payload);
                    if (!type.getComponentType().isPrimitive()) {
                        for (int i = 0; i < length; i++) {
                            Object child = Array.get(value, i);
                            if (child != null) queue.add(new Item(child, null, item.origin + "[" + i + "]"));
                        }
                    }
                    continue;
                }
                List<Field> fields = refs(type);
                boolean boundary = type == RingBuffer.class;
                if (!boundary && !new HashSet<>(fields.stream().map(Field::getName).toList()).equals(FIELDS.get(type))) {
                    throw new IllegalStateException("reference inventory changed: " + type.getName());
                }
                List<Object> inventory = new ArrayList<>();
                for (Field f : fields) {
                    String reason = null, action = "follow";
                    if (boundary) reason = "ring infrastructure descendants excluded; actual event slots measured as explicit separate roots";
                    else if (type == HotPathMetrics.class || type == RiskMetrics.class)
                        reason = "metrics descendant/JDK/HdrHistogram graph excluded; metrics wrapper shallow only";
                    else if (type == Int2ObjectHashMap.class && !f.getName().equals("keys") && !f.getName().equals("values"))
                        reason = "lazy Agrona iteration adapters excluded from index-storage scope";
                    if (reason != null) action = "excluded";
                    if ((type == MatchingEngine.class && f.getName().equals("snapshotTrigger"))
                        || (type == OutputPublisher.class && f.getName().equals("onBackpressure"))) action = "require_null";
                    inventory.add(row("field", f.getName(), "declaring_class", f.getDeclaringClass().getName(),
                        "type", f.getType().getName(), "action", action, "reason", reason));
                    if (boundary) {
                        excluded.add(row("edge", f.getDeclaringClass().getName() + "." + f.getName(),
                            "target_present", "not inspected at boundary", "reason", reason));
                        continue;
                    }
                    f.setAccessible(true); Object child = f.get(value);
                    if (action.equals("require_null") && child != null) throw new IllegalStateException("unexpected fixture callback");
                    if (action.equals("excluded")) {
                        excluded.add(row("edge", type.getName() + "." + f.getName(), "target_present", child != null, "reason", reason));
                    } else if (child != null) {
                        queue.add(new Item(child, child.getClass().isArray() ? arrayCategory(type) : null,
                            type.getName() + "." + f.getName()));
                    }
                }
                inventories.put(type.getName(), inventory);
            }
        }
        Map<String, Object> result(String phase, MatchingEngine engine, RingBuffer<OutputEvent> ring) throws Exception {
            long total = categories.values().stream().mapToLong(c -> (Long)c.get("shallow_bytes")).sum();
            long engineOwned = categories.entrySet().stream().filter(e -> e.getKey().startsWith("engine_") || e.getKey().startsWith("book_"))
                .mapToLong(e -> (Long)e.getValue().get("shallow_bytes")).sum();
            if (total > 96L * 1024 * 1024) throw new IllegalStateException("measured object byte bound exceeded");
            int books = 0, open = 0;
            for (Object value : seen.keySet()) if (value instanceof LimitBook b) { books++; open += b.openOrders(); }
            int free = 0;
            for (RestingOrder o = (RestingOrder) field(engine, "freeList"); o != null; o = o.nextFree) {
                if (++free > 128) throw new IllegalStateException("free-list bound/cycle");
            }
            PositionBook positions = (PositionBook) field(engine, "positions");
            Int2ObjectHashMap<?> index = (Int2ObjectHashMap<?>) field(engine, "ordersByRef");
            return row("phase", phase, "categories", categories, "array_inventory", arrays,
                "reference_inventory", inventories, "excluded_edges", excluded,
                "measured_unique_object_count", seen.size(), "measured_unique_shallow_bytes", total,
                "engine_owned_unique_shallow_bytes", engineOwned, "supplied_measured_unique_shallow_bytes", total - engineOwned,
                "aliases_skipped", aliases, "observed", row("book_count", books, "resting_orders", open,
                    "free_pool_entries", free, "order_index_size", index.size(), "order_index_capacity", index.capacity(),
                    "position_size", positions.size(), "terminal_count", field(engine, "terminalCount"),
                    "engine_book_levels", engine.bookLevels(), "engine_global_tick_ticks", engine.bookTickPx(),
                    "pending_capacity", engine.orderTypeLimits()[0], "peg_capacity", engine.orderTypeLimits()[1],
                    "events_processed", engine.eventsProcessed(), "applied_sequence", engine.blpSeq(), "output_cursor", ring.getCursor()));
        }
    }

    private static Map<String, Object> phase(String name, Unsafe unsafe, MatchingEngine engine, BlpRiskState risk,
            OutputPublisher out, HotPathMetrics metrics, RiskMetrics riskMetrics, RingBuffer<OutputEvent> ring) throws Exception {
        Graph graph = new Graph(unsafe);
        graph.root(engine, "engine"); graph.root(risk, "supplied risk"); graph.root(out, "supplied output");
        graph.root(metrics, "supplied hot metrics"); graph.root(riskMetrics, "supplied risk metrics");
        graph.root(ring, "supplied ring shallow boundary");
        for (int i = 0; i < ring.getBufferSize(); i++) graph.root(ring.get(i), "real ring slot " + i);
        graph.measure();
        return graph.result(name, engine, ring);
    }
    private static long sequence;
    private static void apply(MatchingEngine engine, InputEvent e) throws Exception {
        e.seq = sequence; e.eventTimeMillis = 1_000_000; e.ingressNanos = System.nanoTime();
        engine.onEvent(e, sequence++, true);
    }
    private static InputEvent event(byte type) { InputEvent e = new InputEvent(); e.type = type; return e; }

    public static void main(String[] args) throws Exception {
        int[] c = Arrays.stream(args).mapToInt(Integer::parseInt).toArray();
        if (c.length != 12) throw new IllegalArgumentException("twelve fixture capacities required");
        int levels=c[0], books=c[1], orders=c[2], pool=c[3], securities=c[4], terminal=c[5], positions=c[6];
        int pending=c[7], pegs=c[8], accounts=c[9], exposures=c[10], idempotency=c[11];
        if (levels < 64 || levels > 4096 || Integer.bitCount(levels) != 1 || books < 1 || books > 4
            || orders < 1 || orders > 8 || pool < 8 || pool < books*orders+1 || pool > 128 || securities < Math.max(4, books)
            || securities > 16 || terminal < 4 || terminal > 128 || positions < 16 || positions > 128
            || pending < 1 || pending > 32 || pegs < 1 || pegs > 32 || accounts < 2 || accounts > 16
            || exposures < 16 || exposures > 256 || idempotency < 8 || idempotency > 128)
            throw new IllegalArgumentException("bounded engine fixture capacities exceeded");
        Field singleton=Unsafe.class.getDeclaredField("theUnsafe"); singleton.setAccessible(true); Unsafe unsafe=(Unsafe)singleton.get(null);
        HotPathMetrics metrics = new HotPathMetrics(); RiskMetrics riskMetrics = new RiskMetrics();
        BlpRiskState risk = new BlpRiskState(accounts, securities, exposures, idempotency,
            Long.MAX_VALUE/4, 10000, Long.MAX_VALUE/8, 60000, riskMetrics);
        RingBuffer<OutputEvent> ring = RingBuffer.createSingleProducer(OutputEvent::new, 256);
        OutputPublisher out = new OutputPublisher(ring);
        com.sun.management.ThreadMXBean allocation = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        allocation.setThreadAllocatedMemoryEnabled(true); long tid = Thread.currentThread().getId();
        long baselineStart=allocation.getThreadAllocatedBytes(tid), baseline=allocation.getThreadAllocatedBytes(tid)-baselineStart;
        long before=allocation.getThreadAllocatedBytes(tid);
        MatchingEngine engine = new MatchingEngine(out, metrics, securities, 0, pool, positions, terminal, risk);
        long allocated=allocation.getThreadAllocatedBytes(tid)-before;
        int constructorLevels=engine.bookLevels(); long constructorTick=engine.bookTickPx();
        engine.setBookGeometry(levels, 1000); engine.setOrderTypeLimits(pending, pegs, 0);
        List<Object> phases = new ArrayList<>();
        phases.add(phase("empty", unsafe, engine, risk, out, metrics, riskMetrics, ring));
        for (int account=1; account<=2; account++) {
            InputEvent e=event(InputEvent.TYPE_ACCOUNT_CONTROL); e.accountId=account; e.side=1; apply(engine,e);
        }
        for (int security=0; security<books; security++) {
            InputEvent e=event(InputEvent.TYPE_SECURITY_CONTROL); e.securityId=security; e.side=1; apply(engine,e);
            e=event(InputEvent.TYPE_PRICE_TICK); e.securityId=security; e.priceTicks=100_000_000L; apply(engine,e);
        }
        for (int b=0; b<books; b++) for (int o=0; o<orders; o++) {
            InputEvent e=event(InputEvent.TYPE_ORDER_NEW); e.accountId=1; e.securityId=b; e.orderRef=b*orders+o+1;
            e.qty=1; e.limitPx=100_000_000L; e.side=InputEvent.SIDE_BUY; apply(engine,e);
        }
        phases.add(phase("resting", unsafe, engine, risk, out, metrics, riskMetrics, ring));
        InputEvent cross=event(InputEvent.TYPE_ORDER_NEW); cross.accountId=2; cross.securityId=0;
        cross.orderRef=books*orders+1; cross.qty=1; cross.limitPx=100_000_000L; cross.side=InputEvent.SIDE_SELL;
        apply(engine,cross);
        if (engine.positionQuantity(1,0)!=1 || engine.positionQuantity(2,0)!=-1
            || engine.orderState(1)[0]!=RestingOrder.STATUS_FILLED || engine.orderState(cross.orderRef)[0]!=RestingOrder.STATUS_FILLED)
            throw new IllegalStateException("real-engine crossing/ordering witness failed");
        phases.add(phase("crossed", unsafe, engine, risk, out, metrics, riskMetrics, ring));
        HotSpotDiagnosticMXBean vm=ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        Map<String,Object> flags=new TreeMap<>();
        for (String flag:List.of("UseCompressedOops","UseCompressedClassPointers","ObjectAlignmentInBytes","MaxHeapSize","InitialHeapSize"))
            flags.put(flag,vm.getVMOption(flag).getValue());
        try { flags.put("UseCompactObjectHeaders",vm.getVMOption("UseCompactObjectHeaders").getValue()); }
        catch (IllegalArgumentException absent) { flags.put("UseCompactObjectHeaders","unavailable on this JVM"); }
        System.out.println("BOOK_MEMORY_JSON:"+json(row("schema",1,"profile","engine-components","units","bytes",
            "fixture",row("book_levels",levels,"book_count",books,"orders_per_book",orders,"initial_pool_entries",pool,
                "max_securities",securities,"terminal_retention",terminal,"position_capacity_requested",positions,
                "pending_capacity",pending,"peg_capacity",pegs,"risk_max_accounts",accounts,"risk_max_open_orders",exposures,
                "risk_idempotency_capacity",idempotency,"output_ring_slots",ring.getBufferSize(),"global_tick_ticks",1000,
                "engine_default_book_levels",MatchingEngine.DEFAULT_BOOK_LEVELS,"constructor_book_levels",constructorLevels,
                "constructor_tick_ticks",constructorTick,"constructor_pending_capacity",OrderTypes.DEFAULT_PENDING_CAPACITY,
                "risk_credit_limit_ticks",Long.MAX_VALUE/4,"risk_max_order_quantity",10000,"risk_max_order_notional_ticks",Long.MAX_VALUE/8,
                "risk_price_max_age_millis",60000,"synthetic_price_ticks",100_000_000L,"event_time_millis",1_000_000),
            "constructor_allocation_sample",row("thread_allocated_bytes",allocated,"noop_probe_bytes",baseline,
                "scope","first real engine constructor only; includes on-thread class initialization/internal construction; supplied collaborators and later geometry/limit overrides outside window; no baseline subtraction or retained-heap claim"),
            "vm",row("java_version",System.getProperty("java.version"),"java_runtime_version",System.getProperty("java.runtime.version"),
                "java_vendor",System.getProperty("java.vendor"),"java_home",System.getProperty("java.home"),"java_vm_name",System.getProperty("java.vm.name"),
                "java_vm_version",System.getProperty("java.vm.version"),"os_name",System.getProperty("os.name"),"os_arch",System.getProperty("os.arch"),
                "flags",flags,"input_arguments",ManagementFactory.getRuntimeMXBean().getInputArguments(),
                "garbage_collectors",ManagementFactory.getGarbageCollectorMXBeans().stream().map(b->b.getName()).toList()),
            "phases",phases,"behavior_witness",row("buyer_position",engine.positionQuantity(1,0),"seller_position",engine.positionQuantity(2,0),
                "first_bid_status",engine.orderState(1)[0],"crossing_status",engine.orderState(cross.orderRef)[0]),
            "unavailable",row("retained_heap_bytes","no GC-root/dominator analysis; reachable sums are not retained heap",
                "full_member_heap_bytes","no member/service/journal/network/Spring hosting; static/JDK/native/off-heap memory excluded",
                "metrics_descendant_bytes","HdrHistogram/LongAdder/atomic/concurrent-map internals excluded explicitly",
                "ring_infrastructure_descendant_bytes","ring wrapper shallow and actual slots measured; sequencer/wait strategy/entries array infrastructure excluded"))));
    }
    private static String json(Object value) {
        if (value==null) return "null";
        if (value instanceof String text) {
            StringBuilder s=new StringBuilder("\"");
            for (char c:text.toCharArray()) {
                if (c=='\\'||c=='"') s.append('\\').append(c);
                else if (c<32) s.append(String.format("\\u%04x",(int)c)); else s.append(c);
            }
            return s.append('"').toString();
        }
        List<String> items=new ArrayList<>();
        if (value instanceof Map<?,?> map) { for (var e:map.entrySet()) items.add(json(e.getKey())+":"+json(e.getValue())); return "{"+String.join(",",items)+"}"; }
        if (value instanceof Iterable<?> list) { for(Object item:list)items.add(json(item));return "["+String.join(",",items)+"]"; }
        if (value instanceof Number||value instanceof Boolean)return value.toString();
        throw new IllegalArgumentException("unsupported report JSON type");
    }
}
