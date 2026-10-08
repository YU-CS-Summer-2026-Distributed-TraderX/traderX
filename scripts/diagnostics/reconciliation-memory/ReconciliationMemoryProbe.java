package finos.traderx.tradeprocessor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.net.httpserver.HttpServer;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import sun.misc.Unsafe;

/** Synthetic bounded actual-method observer; no database/Spring application. */
public final class ReconciliationMemoryProbe {
    private static Instrumentation agent;
    private static Unsafe unsafe;
    private static Map<String,Object> capture;
    private static int captures;
    public static void premain(String ignored, Instrumentation instrumentation) { agent=instrumentation; }
    private static Map<String,Object> row(Object... values) {
        Map<String,Object> map=new LinkedHashMap<>();
        for(int i=0;i<values.length;i+=2)map.put((String)values[i],values[i+1]);
        return map;
    }
    private static Object field(Object value,String name) throws Exception {
        for(Class<?> type=value.getClass();type!=null;type=type.getSuperclass()) {
            try {Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(value);}
            catch(NoSuchFieldException next) { }
        }
        throw new IllegalStateException("field unavailable: "+name);
    }
    private static final Map<String,Set<String>> REFS=Map.of(
        "java.lang.String",Set.of("value"),"java.time.Instant",Set.of(),
        "java.util.ArrayList",Set.of("elementData"),"java.util.ArrayList$SubList",Set.of("root","parent"),
        "java.util.HashSet",Set.of("map"),"java.util.HashMap",Set.of("table","entrySet","keySet","values"),
        "java.util.HashMap$Node",Set.of("key","value","next"),
        "finos.traderx.tradeprocessor.service.ReconciliationService$OrphanSweepResult",Set.of("sweptAt","orphanIds"));
    private static List<Field> references(Class<?> type) {
        List<Field> fields=new ArrayList<>();
        for(Class<?> current=type;current!=null;current=current.getSuperclass())for(Field f:current.getDeclaredFields())
            if(!Modifier.isStatic(f.getModifiers())&&!f.getType().isPrimitive())fields.add(f);
        fields.sort(Comparator.comparing(Field::getName));return fields;
    }
    private static Map<String,Object> graph(Object... roots) throws Exception {
        IdentityHashMap<Object,Boolean> seen=new IdentityHashMap<>();ArrayDeque<Object> queue=new ArrayDeque<>();
        for(Object root:roots)queue.add(root);
        Map<String,Map<String,Object>> classes=new TreeMap<>();Map<String,Object> inventory=new TreeMap<>();
        long shallow=0,payload=0,arrayShallow=0,arrayBases=0,arrayPadding=0;int strings=0,stringArrays=0,aliases=0,sharedSentinels=0;
        Field present=HashSet.class.getDeclaredField("PRESENT");present.setAccessible(true);Object sentinel=present.get(null);
        while(!queue.isEmpty()) {
            Object value=queue.remove();
            if(value==sentinel){sharedSentinels++;continue;} // Shared static token, not owned storage.
            if(seen.put(value,true)!=null){aliases++;continue;}
            if(seen.size()>25000)throw new IllegalStateException("selected graph bound exceeded");
            Class<?> type=value.getClass();long bytes=agent.getObjectSize(value);shallow+=bytes;
            Map<String,Object> count=classes.computeIfAbsent(type.getName(),key->row("count",0,"shallow_bytes",0L,"array_payload_bytes",0L));
            count.put("count",(Integer)count.get("count")+1);count.put("shallow_bytes",(Long)count.get("shallow_bytes")+bytes);
            if(type.isArray()) {
                int length=Array.getLength(value),scale=unsafe.arrayIndexScale(type),base=unsafe.arrayBaseOffset(type);
                long elements=(long)length*scale;
                if(bytes<elements+base)throw new IllegalStateException("invalid array layout");
                payload+=elements;arrayShallow+=bytes;arrayBases+=base;arrayPadding+=bytes-base-elements;count.put("array_payload_bytes",(Long)count.get("array_payload_bytes")+elements);
                if(type==byte[].class||type==char[].class)stringArrays++;
                if(!type.getComponentType().isPrimitive())for(int i=0;i<length;i++){Object child=Array.get(value,i);if(child!=null)queue.add(child);}
                continue;
            }
            if(value instanceof String)strings++;
            List<Field> refs=references(type);
            if(!new HashSet<>(refs.stream().map(Field::getName).toList()).equals(REFS.get(type.getName())))
                throw new IllegalStateException("unknown selected graph reference layout: "+type.getName());
            List<String> names=new ArrayList<>();
            for(Field f:refs){f.setAccessible(true);names.add(f.getName());Object child=f.get(value);if(child!=null)queue.add(child);}
            inventory.put(type.getName(),names);
        }
        if(shallow>16L*1024*1024)throw new IllegalStateException("selected graph bytes bound exceeded");
        return row("unique_objects",seen.size(),"selected_shallow_bytes",shallow,"array_payload_bytes",payload,
            "array_shallow_bytes",arrayShallow,"array_base_offset_bytes",arrayBases,"array_alignment_padding_bytes",arrayPadding,
            "string_objects",strings,"string_storage_arrays",stringArrays,"aliases_skipped",aliases,
            "classes",classes,"reference_inventory",inventory,"excluded_shared_static_hashset_sentinel_edges",sharedSentinels,
            "scope","identity-deduplicated selected collection/result graph, including Strings; not exclusive ownership/dominator retained heap");
    }
    public static void capture(Set<String> history,List<String> local,List<String> orphans) {
        try {
            if(++captures!=1)throw new IllegalStateException("observer called more than once");
            capture=row("history_unique_ids",history.size(),"local_id_rows",local.size(),"local_unique_ids",new HashSet<>(local).size(),
                "orphan_rows",orphans.size(),"orphan_unique_ids",new HashSet<>(orphans).size(),
                "history_selected_graph",graph(history),"local_ids_selected_graph",graph(local),
                "all_orphans_selected_graph",graph(orphans),"temporary_selected_union",graph(history,local,orphans),
                "observation_point","after all history pages/local IDs/orphans collected, before result construction; scalar snapshots only, no saved input roots");
        } catch(Exception error){throw new IllegalStateException("temporary observer failed",error);}
    }
    private static Map<String,Object> backing(List<String> view) throws Exception {
        List<?> root=view;
        if(view.getClass().getName().equals("java.util.ArrayList$SubList"))root=(List<?>)field(view,"root");
        if(root.getClass()!=ArrayList.class)throw new IllegalStateException("unsupported orphan result backing type");
        Object[] storage=(Object[])field(root,"elementData");int nonnull=0,hidden=0;
        for(int i=0;i<storage.length;i++)if(storage[i]!=null){nonnull++;if(i>=view.size())hidden++;}
        return row("view_class",view.getClass().getName(),"visible_ids",view.size(),"root_list_size",root.size(),
            "backing_array_capacity",storage.length,"backing_nonnull_slots",nonnull,"slots_beyond_visible_view",hidden,
            "backing_array_shallow_bytes",agent.getObjectSize(storage),"backing_array_payload_bytes",(long)storage.length*unsafe.arrayIndexScale(storage.getClass()));
    }
    public static void main(String[] args) throws Exception {
        if(Runtime.version().feature()!=21)throw new IllegalArgumentException("layout-pinned diagnostic requires Java21");
        int history=Integer.parseInt(args[0]),orphans=Integer.parseInt(args[1]),historyRepeat=Integer.parseInt(args[2]),localRepeat=Integer.parseInt(args[3]),pageSize=Integer.parseInt(args[4]);
        if(history<0||history>2000||orphans<0||orphans>2000||history+orphans>3000||historyRepeat<1||historyRepeat>2||localRepeat<1||localRepeat>2||pageSize<16||pageSize>256)
            throw new IllegalArgumentException("bounded synthetic fixture inputs exceeded");
        Field single=Unsafe.class.getDeclaredField("theUnsafe");single.setAccessible(true);unsafe=(Unsafe)single.get(null);
        List<String> local=new ArrayList<>();
        for(int i=0;i<history;i++){String id=String.format(Locale.ROOT,"H%06d",i);for(int r=0;r<localRepeat;r++)local.add(id);}
        for(int i=0;i<orphans;i++){String id=String.format(Locale.ROOT,"O%06d",i);for(int r=0;r<localRepeat;r++)local.add(id);}
        AtomicInteger repositoryCalls=new AtomicInteger(),reindexCalls=new AtomicInteger(),pageCalls=new AtomicInteger(),servedRows=new AtomicInteger();
        TradeRepository repository=(TradeRepository)Proxy.newProxyInstance(TradeRepository.class.getClassLoader(),new Class<?>[]{TradeRepository.class},(p,method,arguments)->{
            if(method.getName().equals("findAllIds")){repositoryCalls.incrementAndGet();return local;}
            if(method.getName().equals("toString"))return "owned synthetic read-only repository";
            if(method.getName().equals("hashCode"))return System.identityHashCode(p);
            if(method.getName().equals("equals"))return p==arguments[0];
            throw new IllegalStateException("unexpected repository operation: "+method.getName());
        });
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ExecutorService executor=Executors.newFixedThreadPool(1);server.setExecutor(executor);
        server.createContext("/recon/full-history",exchange->{
            String response="{}";int status=200;
            try {
                if(!exchange.getRequestHeaders().getFirst("Authorization").startsWith("Bearer "))throw new IllegalStateException("actual auth header absent");
                if(exchange.getRequestURI().getPath().endsWith("/reindex")&&exchange.getRequestMethod().equals("POST"))reindexCalls.incrementAndGet();
                else if(exchange.getRequestURI().getPath().endsWith("/trades")&&exchange.getRequestMethod().equals("GET")) {
                    pageCalls.incrementAndGet();long since=Long.parseLong(exchange.getRequestURI().getRawQuery().substring("sinceSeq=".length()));
                    StringBuilder json=new StringBuilder("[");int n=0;
                    for(long seq=since+1;seq<=history*historyRepeat&&n<pageSize;seq++,n++) {
                        if(n>0)json.append(',');String id=String.format(Locale.ROOT,"H%06d",(seq-1)%Math.max(1,history));
                        json.append("{\"id\":\"").append(id).append("\",\"tradeSeq\":").append(seq)
                            .append(",\"accountId\":1,\"security\":\"SYNTHETIC\",\"side\":\"Buy\",\"quantity\":1,\"price\":1.0,\"execTimeMillis\":1}");
                    }
                    servedRows.addAndGet(n);response=json.append(']').toString();
                } else throw new IllegalStateException("unexpected synthetic route/method");
            } catch(Exception failed){status=500;response="{\"error\":\"synthetic fixture refused\"}";}
            byte[] body=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}exchange.close();
        });
        SimpleMeterRegistry meters=new SimpleMeterRegistry();ReconciliationService service=null;
        try {
            server.start();service=new ReconciliationService(repository,"http://127.0.0.1:"+server.getAddress().getPort(),"owned-offline-diagnostic-key",meters);
            if(service.lastOrphanSweep()!=null)throw new IllegalStateException("fixture result already present");
            var result=service.runOrphanSweep();
            if(captures!=1||capture==null||result!=service.lastOrphanSweep())throw new IllegalStateException("actual method/result retention not observed");
            Field capField=ReconciliationService.class.getDeclaredField("MAX_REPORTED_ORPHANS");capField.setAccessible(true);int cap=capField.getInt(null);
            var boundedCopy=new ReconciliationService.OrphanSweepResult(result.sweptAt(),result.localTradeCount(),result.fullHistoryTradeCount(),result.orphanCount(),new ArrayList<>(result.orphanIds()));
            if(!boundedCopy.orphanIds().equals(result.orphanIds()))throw new IllegalStateException("bounded copy comparator changed visible output");
            HotSpotDiagnosticMXBean vm=ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            Map<String,Object> flags=new TreeMap<>();for(String name:List.of("UseCompressedOops","UseCompressedClassPointers","ObjectAlignmentInBytes","MaxHeapSize","InitialHeapSize"))flags.put(name,vm.getVMOption(name).getValue());
            Map<String,Object> report=row("schema",1,"units","bytes","actual_method_executed",true,"mode","unmanaged actual full service, mocked read-only repository, owned synthetic loopback",
                "fixture",row("history_unique_requested",history,"orphan_unique_requested",orphans,"history_repeat",historyRepeat,"local_repeat",localRepeat,"page_size",pageSize,"actual_reporting_cap",cap),
                "http_witness",row("reindex_posts",reindexCalls.get(),"page_gets",pageCalls.get(),"served_history_rows",servedRows.get(),"repository_reads",repositoryCalls.get()),
                "temporary",capture,"actual_result",row("local_trade_count",result.localTradeCount(),"full_history_trade_count",result.fullHistoryTradeCount(),"orphan_count",result.orphanCount(),"reported_ids",result.orphanIds(),"last_result_same_identity",true),
                "last_result_selected_graph",graph(service.lastOrphanSweep()),"actual_backing",backing(result.orphanIds()),
                "bounded_copy_selected_graph",graph(boundedCopy),"bounded_copy_backing",backing(boundedCopy.orphanIds()),
                "negative_comparator","throwaway copy of identical reported IDs/count metadata; no production change or claim of reclaimed heap",
                "vm",row("java_version",System.getProperty("java.version"),"java_runtime_version",System.getProperty("java.runtime.version"),"java_vendor",System.getProperty("java.vendor"),"java_home",System.getProperty("java.home"),"java_vm_name",System.getProperty("java.vm.name"),"java_vm_version",System.getProperty("java.vm.version"),"os_arch",System.getProperty("os.arch"),"os_name",System.getProperty("os.name"),"flags",flags,"input_arguments",ManagementFactory.getRuntimeMXBean().getInputArguments()),
                "unavailable",row("full_process_or_dominator_retained_heap_bytes","selected shallow graph only; repository may share strings; no GC-root/dominator analysis",
                    "all_temporary_allocation_bytes","observer measures populated collection graphs, not HTTP/JSON/page/JIT allocation traffic",
                    "managed_trade_row_materialization_bytes","managed RunRegistry/JDBC path not exercised; compiled only","service_http_mapper_meter_native_graph_bytes","not traversed; outside selected collection/result roots"));
            System.out.println("RECON_MEMORY_JSON:"+new ObjectMapper().writeValueAsString(report));
        } finally {
            if(service!=null)((HttpClient)field(service,"httpClient")).close();
            server.stop(0);executor.shutdown();if(!executor.awaitTermination(5,TimeUnit.SECONDS))executor.shutdownNow();meters.close();
        }
    }
}
