package finos.traderx.ordermatcher.lmax;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sun.misc.Unsafe;

/** Disposable diagnostic agent/fixture; never loaded by a TraderX service. */
public final class BookMemoryProbe {
    private static Instrumentation instrumentation;

    public static void premain(String ignored, Instrumentation supplied) {
        instrumentation = supplied;
    }

    private static long size(Object value) {
        if (instrumentation == null) throw new IllegalStateException("javaagent required");
        return instrumentation.getObjectSize(value);
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }

    private static List<Field> references(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) {
                field.setAccessible(true);
                fields.add(field);
            }
        }
        fields.sort(Comparator.comparing(Field::getName));
        return fields;
    }

    private static Map<String, Object> inspect(LimitBook book, Unsafe unsafe,
                                               IdentityHashMap<Object, Boolean> pool) throws Exception {
        long payload = 0, arrayShallow = 0, baseBytes = 0, paddingBytes = 0;
        List<Object> arrays = new ArrayList<>();
        IdentityHashMap<Object, Boolean> owned = new IdentityHashMap<>();
        owned.put(book, true);
        ArrayDeque<Object> queue = new ArrayDeque<>();
        for (Field field : references(LimitBook.class)) {
            Object array = field.get(book);
            if (array == null || !array.getClass().isArray()) {
                throw new IllegalStateException("unclassified book reference: " + field.getName());
            }
            if (owned.put(array, true) != null) throw new IllegalStateException("aliased owned array");
            int length = Array.getLength(array);
            int scale = unsafe.arrayIndexScale(array.getClass());
            int base = unsafe.arrayBaseOffset(array.getClass());
            long bytes = size(array), elements = (long) length * scale;
            long padding = bytes - base - elements;
            if (padding < 0) throw new IllegalStateException("invalid array size");
            arrays.add(row("field", field.getName(), "type", array.getClass().getName(),
                "length", length, "element_bytes", scale, "payload_bytes", elements,
                "base_offset_bytes", base, "alignment_padding_bytes", padding,
                "shallow_bytes", bytes));
            payload += elements;
            arrayShallow += bytes;
            baseBytes += base;
            paddingBytes += padding;
            if (!array.getClass().getComponentType().isPrimitive()) {
                for (int i = 0; i < length; i++) {
                    Object value = Array.get(array, i);
                    if (value != null) queue.add(value);
                }
            }
        }
        IdentityHashMap<Object, Boolean> reachable = new IdentityHashMap<>();
        long sharedBytes = 0;
        List<Field> links = references(RestingOrder.class);
        while (!queue.isEmpty()) {
            Object value = queue.remove();
            if (reachable.put(value, true) != null) continue;
            if (!(value instanceof RestingOrder) || !pool.containsKey(value)) {
                throw new IllegalStateException("unclassified object reached from book");
            }
            sharedBytes += size(value);
            for (Field link : links) {
                Object target = link.get(value);
                if (target != null) queue.add(target);
            }
        }
        if (reachable.size() != book.openOrders()) throw new IllegalStateException("fixture reachability mismatch");
        return row("levels", book.levels(), "tick_ticks", book.tickTicks(),
            "open_orders", book.openOrders(), "arrays", arrays,
            "array_payload_bytes", payload, "array_base_offset_bytes", baseBytes,
            "array_alignment_padding_bytes", paddingBytes, "array_shallow_bytes", arrayShallow,
            "book_object_shallow_bytes", size(book), "book_owned_object_count", owned.size(),
            "book_owned_reachable_shallow_bytes", size(book) + arrayShallow,
            "shared_pool_reachable_order_count", reachable.size(),
            "shared_pool_reachable_order_shallow_bytes", sharedBytes,
            "book_and_shared_reachable_union_shallow_bytes", size(book) + arrayShallow + sharedBytes);
    }

    public static void main(String[] args) throws Exception {
        int levels = Integer.parseInt(args[0]), count = Integer.parseInt(args[1]);
        int orders = Integer.parseInt(args[2]);
        long tick = Long.parseLong(args[3]);
        if (levels < 64 || levels > 131072 || Integer.bitCount(levels) != 1
                || count < 1 || count > 4 || orders < 0 || orders > 32 || tick < 1
                || tick > 1000000) throw new IllegalArgumentException("fixture exceeds diagnostic bounds");
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Unsafe unsafe = (Unsafe) singleton.get(null);
        HotSpotDiagnosticMXBean vm = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        Map<String, Object> flags = new LinkedHashMap<>();
        for (String name : List.of("UseCompressedOops", "UseCompressedClassPointers",
                "ObjectAlignmentInBytes", "MaxHeapSize", "InitialHeapSize")) {
            flags.put(name, vm.getVMOption(name).getValue());
        }
        try {
            flags.put("UseCompactObjectHeaders", vm.getVMOption("UseCompactObjectHeaders").getValue());
        } catch (IllegalArgumentException unavailable) {
            flags.put("UseCompactObjectHeaders", "unavailable on this JVM");
        }
        // Explicit fixture owner preallocates the entries before ANY book is constructed.
        // The production engine/free-list/output rings are not instantiated or measured.
        RestingOrder[] reservoir = new RestingOrder[Math.max(8, count * orders)];
        IdentityHashMap<Object, Boolean> pool = new IdentityHashMap<>();
        long entryBytes = 0;
        for (int i = 0; i < reservoir.length; i++) {
            reservoir[i] = new RestingOrder();
            pool.put(reservoir[i], true);
            entryBytes += size(reservoir[i]);
        }
        List<Object> empty = new ArrayList<>(), occupied = new ArrayList<>();
        long totalOwned = 0;
        LimitBook[] books = new LimitBook[count];
        for (int b = 0; b < count; b++) {
            LimitBook book = new LimitBook(levels, tick);
            books[b] = book;
            Map<String, Object> before = inspect(book, unsafe, pool);
            empty.add(before);
            totalOwned += (Long) before.get("book_owned_reachable_shallow_bytes");
            for (int n = 0; n < orders; n++) {
                RestingOrder order = reservoir[b * orders + n];
                order.orderRef = b * orders + n + 1;
                order.side = n % 2 == 0 ? InputEvent.SIDE_BUY : InputEvent.SIDE_SELL;
                order.remaining = order.quantity = 1;
                order.limitPx = (long) (levels / 2) * tick;
                book.append(order, book.slotFor(order.limitPx));
            }
            occupied.add(inspect(book, unsafe, pool));
        }
        System.out.println("BOOK_MEMORY_JSON:" + json(row("schema", 1, "units", "bytes", "measurement", "instrumentation-shallow",
            "vm", row("java_version", System.getProperty("java.version"),
                "java_runtime_version", System.getProperty("java.runtime.version"),
                "java_vendor", System.getProperty("java.vendor"), "java_vm_name", System.getProperty("java.vm.name"),
                "java_vm_version", System.getProperty("java.vm.version"), "java_home", System.getProperty("java.home"),
                "os_name", System.getProperty("os.name"), "os_arch", System.getProperty("os.arch"),
                "input_arguments", ManagementFactory.getRuntimeMXBean().getInputArguments(),
                "garbage_collectors", ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .map(bean -> bean.getName()).toList(),
                "heap_max_bytes", Runtime.getRuntime().maxMemory(), "flags", flags),
            "fixture", row("book_count", count, "levels", levels, "tick_ticks", tick,
                "orders_per_book", orders, "pool_entry_count", reservoir.length,
                "pool_owner", "external disposable fixture reservoir; production engine pool not measured",
                "pool_array_shallow_bytes", size(reservoir), "pool_entries_shallow_bytes", entryBytes,
                "pool_owned_shallow_bytes", size(reservoir) + entryBytes,
                "book_holder_array_shallow_bytes", size(books), "total_book_owned_shallow_bytes", totalOwned,
                "books_plus_entire_fixture_pool_union_shallow_bytes", totalOwned + size(reservoir) + entryBytes),
            "empty_books", empty, "occupied_books", occupied,
            "unavailable", row("constructor_allocated_bytes", "not sampled; shallow object sizes do not measure allocation traffic",
                "retained_heap_bytes", "not measured; no GC-root/dominator analysis",
                "production_pool_output_engine_bytes", "not instantiated; fixture pool is not a full-engine measurement"))));
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) {
            StringBuilder escaped = new StringBuilder("\"");
            for (char c : text.toCharArray()) {
                if (c == '\\' || c == '"') escaped.append('\\').append(c);
                else if (c < 32) escaped.append(String.format("\\u%04x", (int) c));
                else escaped.append(c);
            }
            return escaped.append('"').toString();
        }
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            for (var entry : map.entrySet()) entries.add(json(entry.getKey()) + ":" + json(entry.getValue()));
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof Iterable<?> list) {
            List<String> entries = new ArrayList<>();
            for (Object entry : list) entries.add(json(entry));
            return "[" + String.join(",", entries) + "]";
        }
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        throw new IllegalArgumentException("unsupported JSON type");
    }
}
