package finos.traderx.ordermatcher.lmax;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Banned-API gate (SC-09B13 / SC-NGC-04): asserts the hot-path classes — the code that
 * executes between the gateway edge and the output-handler edge — reference none of the
 * allocation- or blocking-prone APIs the no-GC profile bans: BigDecimal, java.time,
 * HashMap/ConcurrentHashMap, stream pipelines, regex, Atomic*, String.format, string
 * concatenation, SLF4J, Spring, or JPA.
 *
 * The check scans the compiled constant pool (class references and method descriptors
 * appear verbatim as UTF-8 entries), so it catches any reintroduction at build time with
 * no extra tooling.
 *
 * Scope (NGC-01): edge classes are deliberately excluded — Px and SymbolTable are the
 * conversion edges (BigDecimal/ConcurrentHashMap by design), HotPathMetrics renders
 * Prometheus text at scrape time, and LmaxEngine/the output handlers are the allowed
 * allocation edges. The Journaler is scanned with SLF4J permitted: it logs only on the
 * cold open/failure paths, never per event.
 */
class HotPathBannedApiTest {
    private static final Class<?>[] HOT_PATH_CLASSES = {
        MatchingEngine.class, OutputPublisher.class, RestingOrder.class,
        IntList.class, InputEvent.class, OutputEvent.class, ReplicatorStub.class
    };

    private static final String[] BANNED = {
        "java/math/BigDecimal",
        "java/time/Instant",
        "java/util/HashMap",
        "java/util/concurrent/ConcurrentHashMap",
        "java/util/stream/",
        "java/util/regex/",
        "java/util/concurrent/atomic/",
        "org/springframework/",
        "jakarta/persistence/",
        "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;", // String.format
        "makeConcatWithConstants"                                    // string concatenation
    };

    private static final String[] BANNED_LOGGING = {
        "org/slf4j/"
    };

    @Test
    void blpAndRingClassesReferenceNoBannedApis() {
        List<String> violations = new ArrayList<>();
        for (Class<?> hotPathClass : HOT_PATH_CLASSES) {
            String pool = constantPoolText(hotPathClass);
            scan(hotPathClass, pool, BANNED, violations);
            scan(hotPathClass, pool, BANNED_LOGGING, violations);
        }
        assertTrue(violations.isEmpty(), () -> "banned API references on the hot path:\n" + String.join("\n", violations));
    }

    @Test
    void journalerReferencesNoBannedApisBeyondColdPathLogging() {
        List<String> violations = new ArrayList<>();
        scan(Journaler.class, constantPoolText(Journaler.class), BANNED, violations);
        assertTrue(violations.isEmpty(), () -> "banned API references in the journaler:\n" + String.join("\n", violations));
    }

    private static void scan(Class<?> scanned, String pool, String[] banned, List<String> violations) {
        for (String needle : banned) {
            if (pool.contains(needle)) {
                violations.add(scanned.getSimpleName() + " references " + needle);
            }
        }
    }

    private static String constantPoolText(Class<?> scanned) {
        String resource = "/" + scanned.getName().replace('.', '/') + ".class";
        try (InputStream in = scanned.getResourceAsStream(resource)) {
            if (in == null) {
                fail("class bytes not found for " + scanned.getName());
            }
            // Constant-pool UTF-8 entries are ASCII for the names we scan; ISO-8859-1
            // maps every byte 1:1 so substring search over the raw bytes is exact.
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (IOException ex) {
            throw new AssertionError("unable to read class bytes for " + scanned.getName(), ex);
        }
    }
}
