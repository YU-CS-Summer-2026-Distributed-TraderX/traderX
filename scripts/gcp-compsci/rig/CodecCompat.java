import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR-096 §6 (FR-PRG-10): a tip gateway's ingress, decoded by 00b31d33 members. Loads each side's REAL compiled codec
 * (AeronReplicationCodec, InputEvent and the SBE classes generated from that commit's schema) in its own class loader
 * and, for every command type the old members know, encodes the same event with both: the bytes must be identical, and
 * the old decoder must read the tip's bytes back field for field. Flags 0 and SHADOW are the ones an unflagged gateway
 * sends; a sequenced message (ADR-090, never sent while FIX_EXACTLY_ONCE and the binary port are off) is the control
 * that must differ, and a changed field must be caught.
 *
 *   java CodecCompat.java <old classes dir> <tip classes dir> <agrona jar>      exit 0 = compatible
 */
public class CodecCompat {
    static final String PKG = "finos.traderx.ordermatcher.lmax.";

    static final class Side {
        final String name; final ClassLoader cl; final Class<?> codec, event, unsafe, mutable, direct;
        Side(final String name, final String classes, final String agrona) throws Exception {
            this.name = name;
            cl = new URLClassLoader(new URL[] {Path.of(classes).toUri().toURL(), Path.of(agrona).toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
            codec = cl.loadClass(PKG + "AeronReplicationCodec");
            event = cl.loadClass(PKG + "InputEvent");
            unsafe = cl.loadClass("org.agrona.concurrent.UnsafeBuffer");
            mutable = cl.loadClass("org.agrona.MutableDirectBuffer");
            direct = cl.loadClass("org.agrona.DirectBuffer");
        }
        Object newCodec() throws Exception { return codec.getConstructor().newInstance(); }
        Object buffer(final byte[] bytes) throws Exception { return unsafe.getConstructor(byte[].class).newInstance((Object) bytes); }
        Object newEvent(final Map<String, Long> values) throws Exception {
            final Object e = event.getConstructor().newInstance();
            for (final Map.Entry<String, Long> v : values.entrySet()) {
                final Field f = event.getField(v.getKey());
                final long x = v.getValue();
                if (f.getType() == byte.class) f.setByte(e, (byte) x);
                else if (f.getType() == int.class) f.setInt(e, (int) x);
                else f.setLong(e, x);
            }
            return e;
        }
        int constant(final String field) throws Exception {
            final Field f = codec.getDeclaredField(field); f.setAccessible(true); return f.getInt(null);
        }
        List<String> eventFields() {
            final List<String> names = new ArrayList<>();
            for (final Field f : event.getFields()) if (!Modifier.isStatic(f.getModifiers())) names.add(f.getName());
            return names;
        }
        Map<String, Byte> types() throws Exception {
            final Map<String, Byte> types = new LinkedHashMap<>();
            for (final Field f : event.getFields())
                if (Modifier.isStatic(f.getModifiers()) && f.getName().startsWith("TYPE_") && f.getType() == byte.class)
                    types.put(f.getName(), f.getByte(null));
            return types;
        }
        byte[] encode(final String method, final Map<String, Long> values, final int flags) throws Exception {
            final byte[] bytes = new byte[512];
            final Method m = codec.getMethod(method, mutable, int.class, event, long.class, long.class, int.class);
            m.invoke(newCodec(), buffer(bytes), 0, newEvent(values), 0x0102030405060708L, 7L, flags);
            return bytes;
        }
    }

    static int failures = 0, checks = 0;
    static void check(final boolean ok, final String what) {
        checks++;
        if (!ok) { failures++; System.out.println("FAIL " + what); }
    }

    public static void main(final String[] args) throws Exception {
        final Side old = new Side("00b31d33", args[0], args[2]);
        final Side tip = new Side("tip", args[1], args[2]);
        final int sequenced = tip.constant("INPUT_FLAG_SEQUENCED");
        final int shadow = old.constant("INPUT_FLAG_SHADOW");
        check(shadow == tip.constant("INPUT_FLAG_SHADOW"), "INPUT_FLAG_SHADOW moved");
        final int inputBytes = old.constant("INPUT_BYTES");
        final int instructionBytes = old.constant("ORDER_INSTRUCTION_BYTES");
        check(inputBytes == tip.constant("INPUT_BYTES"), "template 1 length moved");
        check(instructionBytes == tip.constant("ORDER_INSTRUCTION_BYTES"), "template 9 length moved");

        // Every field both sides encode, set to a distinct value that fits its type.
        final List<String> common = new ArrayList<>(old.eventFields());
        common.retainAll(tip.eventFields());
        final Map<String, Byte> types = old.types();
        int n = 0;
        for (final Map.Entry<String, Byte> type : types.entrySet()) {
            check(type.getValue().equals(tip.types().get(type.getKey())), type.getKey() + " renumbered on the tip");
            for (final String method : new String[] {"encodeInput", "encodeOrderInstruction"}) {
                final boolean typed = method.equals("encodeOrderInstruction");
                if (typed && !(type.getKey().equals("TYPE_ORDER_NEW") || type.getKey().equals("TYPE_ORDER_REPLACE"))) continue;
                for (final int flags : new int[] {0, shadow}) {
                    final Map<String, Long> v = new LinkedHashMap<>();
                    long seed = 11 + 97L * type.getValue() + flags;
                    for (final String f : common) {
                        if (f.equals("seq") || f.equals("ingressNanos") || f.equals("admissionSeq")
                            || f.equals("streamId") || f.equals("streamSeq")) continue;   // not on the wire
                        final Class<?> t = old.event.getField(f).getType();
                        v.put(f, t == byte.class ? (seed++ % 5) : t == int.class ? 1000 + seed++ : 1_000_000L * seed++);
                    }
                    v.put("type", (long) type.getValue());
                    v.put("side", (long) (flags == 0 ? 0 : 1));
                    final byte[] a = old.encode(method, v, flags), b = tip.encode(method, v, flags);
                    check(java.util.Arrays.equals(a, b), method + " " + type.getKey() + " flags=" + flags + ": tip bytes differ");
                    // The old member decodes the tip's bytes back to the same fields.
                    final Object target = old.event.getConstructor().newInstance();
                    final String decode = typed ? "tryDecodeOrderInstruction" : "tryDecodeInput";
                    final int length = typed ? instructionBytes : inputBytes;
                    final Method d = old.codec.getMethod(decode, old.direct, int.class, int.class, old.event);
                    final Object codec = old.newCodec();
                    final int status = (int) d.invoke(codec, old.buffer(b), 0, length, target);
                    check(status == 0, decode + " " + type.getKey() + " flags=" + flags + ": old decoder status " + status);
                    for (final Map.Entry<String, Long> e : v.entrySet()) {
                        final Field f = old.event.getField(e.getKey());
                        if (!typed && (e.getKey().equals("orderType") || e.getKey().equals("tif") || e.getKey().equals("pegRef")
                            || e.getKey().equals("trailMode") || e.getKey().equals("displayQty") || e.getKey().equals("pegOffset")
                            || e.getKey().equals("stopPx") || e.getKey().equals("trailValue"))) continue;   // template 9 only
                        final long got = f.getType() == byte.class ? f.getByte(target) : f.getType() == int.class
                            ? f.getInt(target) : f.getLong(target);
                        final long want = f.getType() == byte.class ? (byte) (long) e.getValue()
                            : f.getType() == int.class ? (int) (long) e.getValue() : e.getValue();
                        check(got == want, decode + " " + type.getKey() + "." + e.getKey() + ": " + got + " != " + want);
                    }
                    n++;
                }
            }
        }
        // Symbol registration, which a gateway sends for a ticker it has not resolved yet.
        final byte[] sa = new byte[512], sb = new byte[512];
        old.codec.getMethod("encodeSymbolRegister", old.mutable, int.class, long.class, String.class)
            .invoke(old.newCodec(), old.buffer(sa), 0, 42L, "MSFT");
        tip.codec.getMethod("encodeSymbolRegister", tip.mutable, int.class, long.class, String.class)
            .invoke(tip.newCodec(), tip.buffer(sb), 0, 42L, "MSFT");
        check(java.util.Arrays.equals(sa, sb), "encodeSymbolRegister: tip bytes differ");

        // Controls: the comparison catches a changed field, and a sequenced message is not what an old member reads.
        final Map<String, Long> base = new LinkedHashMap<>(Map.of("type", (long) types.get("TYPE_ORDER_NEW"), "qty", 100L));
        final Map<String, Long> changed = new LinkedHashMap<>(base); changed.put("qty", 101L);
        final boolean caught = !java.util.Arrays.equals(old.encode("encodeInput", base, 0), tip.encode("encodeInput", changed, 0));
        final byte[] seq = tip.encode("encodeInput", base, sequenced);
        final boolean seqDiffers = !java.util.Arrays.equals(old.encode("encodeInput", base, 0), seq);
        System.out.println("control: changed qty caught=" + caught + ", sequenced message differs=" + seqDiffers);
        check(caught && seqDiffers, "a control did not fire: the comparison cannot fail");

        System.out.println((failures == 0 ? "COMPATIBLE" : "INCOMPATIBLE") + ": " + n + " encodings over "
            + types.size() + " command types, " + checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }
}
