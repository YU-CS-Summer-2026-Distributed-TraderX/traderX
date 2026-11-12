package hxb.probe;

import io.aeron.CncFileDescriptor;
import org.agrona.IoUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.CountersReader;

import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

/**
 * hxb stall probe (never TraderX). Runs next to the member JVM in the same container, from START to END wall ms:
 *  - every 1 ms: a fixed-then-growing set of the member's CnC counters (system counters, archive 100-199, cluster 200-299,
 *    every IPC publication position) plus three aggregates over all publications/subscriptions (sum pub-pos, min pub
 *    headroom lmt-pos, sum sub-pos), delta-encoded to counters.bin; counters.reg names each slot as it appears.
 *  - every 10 ms: one text line in os.log: Aeron agent threads' state/wchan/schedstat/cpu, memcg memory.stat/pressure,
 *    io/cpu pressure, cpu.stat throttling, node vmstat/steal/softirq/UDP errors.
 * All times are node wall micros (currentTimeMillis anchor + nanoTime). Args: memberPid cncDir outDir startMs endMs
 */
public final class HxProbe {
    static final long CAP_BYTES = Long.getLong("hx.capBytes", 150L << 20);
    static final Pattern AGENT = Pattern.compile("(?i).*(archive|consensus|cluster|driver|sender|receiver|conductor|aeron).*");

    public static void main(final String[] a) throws Exception {
        final int pid = Integer.parseInt(a[0]);
        final File cnc = new File(a[1], CncFileDescriptor.CNC_FILE);
        final File out = new File(a[2]);
        final long startMs = Long.parseLong(a[3]), endMs = Long.parseLong(a[4]);
        out.mkdirs();
        while (System.currentTimeMillis() < startMs) Thread.sleep(200);
        while (!cnc.exists()) Thread.sleep(200);
        final MappedByteBuffer mb = IoUtil.mapExistingFile(cnc, "cnc");
        final UnsafeBuffer meta = CncFileDescriptor.createMetaDataBuffer(mb);
        final CountersReader cr = new CountersReader(
            CncFileDescriptor.createCountersMetaDataBuffer(mb, meta), CncFileDescriptor.createCountersValuesBuffer(mb, meta));

        final long n0 = System.nanoTime(), w0 = System.currentTimeMillis() * 1000;
        final List<Integer> slots = new ArrayList<>();                  // counter ids, index = slot; aggregates are slots 0..2
        final Set<Integer> known = new HashSet<>();
        long[] prev = new long[0];
        try (DataOutputStream bin = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(out, "counters.bin")), 1 << 20));
             PrintWriter reg = new PrintWriter(new FileWriter(new File(out, "counters.reg")));
             PrintWriter os = new PrintWriter(new BufferedWriter(new FileWriter(new File(out, "os.log")), 1 << 20))) {
            reg.printf("ANCHOR wallMicros=%d pid=%d%n", w0, pid);
            reg.println("SLOT 0 -1 -1 AGG sum pub-pos (type 12)"); reg.println("SLOT 1 -1 -1 AGG min pub headroom pub-lmt - pub-pos");
            reg.println("SLOT 2 -1 -1 AGG sum sub-pos (type 4)");
            for (int i = 0; i < 3; i++) slots.add(-1 - i);
            long tick = 0, lastWall = w0;
            final Map<Long, Integer> lmtByReg = new HashMap<>(), posByReg = new HashMap<>();
            final List<Integer> subPos = new ArrayList<>();
            List<int[]> threads = List.of();
            while (true) {
                final long wall = w0 + (System.nanoTime() - n0) / 1000;
                if (wall / 1000 >= endMs) break;
                if (tick % 50 == 0) {                                   // rescan the counter set every 50 ms
                    lmtByReg.clear(); posByReg.clear(); subPos.clear();
                    final long now = wall;
                    cr.forEach((id, type, key, label) -> {
                        if (type == 1) lmtByReg.put(cr.getCounterRegistrationId(id), id);
                        if (type == 12) posByReg.put(cr.getCounterRegistrationId(id), id);
                        if (type == 4) subPos.add(id);
                        final boolean want = (type >= 100 && type < 300) || label.contains("alias=log") || label.contains("alias=snapshot") || label.contains("exceeded");   // latency-tail: the log, snapshot, archive and cluster counters only
                        if (want && known.add(id)) { reg.printf("SLOT %d %d %d %s atMicros=%d%n", slots.size(), id, type, label, now); slots.add(id); }
                    });
                    reg.flush();
                    if (prev.length < slots.size()) prev = Arrays.copyOf(prev, slots.size());
                }
                if (tick % 1000 == 0) threads = agentThreads(pid);
                long sumPos = 0, minHead = Long.MAX_VALUE, sumSub = 0;
                for (final Map.Entry<Long, Integer> e : posByReg.entrySet()) {
                    final long p = cr.getCounterValue(e.getValue()); sumPos += p;
                    final Integer l = lmtByReg.get(e.getKey());
                    if (l != null) minHead = Math.min(minHead, cr.getCounterValue(l) - p);
                }
                for (final int id : subPos) sumSub += cr.getCounterValue(id);
                writeVarLong(bin, wall - lastWall); lastWall = wall;
                writeVarLong(bin, slots.size());
                for (int i = 0; i < slots.size(); i++) {
                    final int id = slots.get(i);
                    final long v = id == -1 ? sumPos : id == -2 ? (minHead == Long.MAX_VALUE ? 0 : minHead) : id == -3 ? sumSub
                        : cr.getCounterState(id) == CountersReader.RECORD_ALLOCATED ? cr.getCounterValue(id) : prev[i];
                    writeVarLong(bin, zig(v - prev[i])); prev[i] = v;
                }
                if (tick % 10 == 0) os.println(osLine(wall, pid, threads));
                if (tick % 1000 == 0) { bin.flush(); os.flush();
                    // latency-tail: /dev/shm is 1Gi and shared with Aeron's buffers (2026-11-12: a full volume failed the leader's driver)
                    if (new File(out, "counters.bin").length() + new File(out, "os.log").length() > CAP_BYTES) { reg.printf("CAPPED ticks=%d%n", tick); break; } }
                tick++;
                final long next = n0 + tick * 1_000_000L;
                long d;
                while ((d = next - System.nanoTime()) > 0) LockSupport.parkNanos(d);
            }
            reg.printf("END ticks=%d%n", tick);
        }
    }

    static long zig(final long v) { return (v << 1) ^ (v >> 63); }

    static void writeVarLong(final DataOutputStream o, long v) throws IOException {
        while ((v & ~0x7FL) != 0) { o.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
        o.write((int) v);
    }

    static List<int[]> agentThreads(final int pid) {
        final List<int[]> r = new ArrayList<>();
        final File[] ts = new File("/proc/" + pid + "/task").listFiles();
        if (ts != null) for (final File t : ts) {
            final String comm = read(new File(t, "comm")).trim();
            if (AGENT.matcher(comm).matches() || comm.startsWith("Z")) r.add(new int[]{Integer.parseInt(t.getName())});
        }
        return r;
    }

    static String read(final File f) {
        try { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII); } catch (IOException e) { return ""; }
    }

    static final String[] MEM = {"anon ", "file ", "shmem ", "file_dirty ", "file_writeback ", "pgscan ", "pgsteal ", "workingset_refault_file ", "pgmajfault "};
    static final String[] VM = {"nr_dirty ", "nr_writeback ", "pgscan_direct ", "allocstall_normal ", "allocstall_movable ", "pgsteal_kswapd ", "pgscan_kswapd "};

    /** wall | thread tid comm state wchan run_ns wait_ns utime+stime ... | memcg fields | psi totals | cpu.stat | node */
    static String osLine(final long wall, final int pid, final List<int[]> threads) {
        final StringBuilder sb = new StringBuilder(1024).append(wall);
        sb.append(" |T");
        long zRun = 0, zWait = 0;
        for (final int[] t : threads) {
            final String base = "/proc/" + pid + "/task/" + t[0] + "/";
            final String stat = read(new File(base + "stat"));
            final int rp = stat.lastIndexOf(')');
            if (rp < 0) continue;
            final String comm = stat.substring(stat.indexOf('(') + 1, rp).replace(' ', '_');
            final String[] f = stat.substring(rp + 2).split(" ");
            final String[] ss = read(new File(base + "schedstat")).trim().split(" ");
            if (comm.startsWith("Z") && ss.length >= 2) { zRun += Long.parseLong(ss[0]); zWait += Long.parseLong(ss[1]); continue; }
            sb.append(' ').append(t[0]).append(':').append(comm).append(':').append(f[0]).append(':')
              .append(read(new File(base + "wchan")).trim()).append(':').append(ss.length >= 2 ? ss[0] + ':' + ss[1] : "?:?")
              .append(':').append(Long.parseLong(f[11]) + Long.parseLong(f[12]));
        }
        sb.append(" Z:").append(zRun).append(':').append(zWait);
        sb.append(" |M");   // latency-tail lean probe: memory.stat (an rstat flush per read) dropped
        sb.append(" |P");
        for (final String p : new String[]{"memory", "io", "cpu"}) {
            final String s = read(new File("/sys/fs/cgroup/" + p + ".pressure"));
            for (final String line : s.split("\n")) { final int i = line.indexOf("total="); if (i > 0) sb.append(' ').append(line.substring(i + 6).trim()); }
        }
        sb.append(" |C");
        final String cs = read(new File("/sys/fs/cgroup/cpu.stat"));
        sb.append(' ').append(field(cs, "nr_throttled ")).append(' ').append(field(cs, "throttled_usec "));
        sb.append(" |N lean");
        return sb.toString();
    }

    static String field(final String s, final String k) {
        final int i = s.startsWith(k) ? 0 : s.indexOf('\n' + k) + 1;
        if (i < 0 || (i == 0 && !s.startsWith(k))) return "?";
        final int e = s.indexOf('\n', i);
        return s.substring(i + k.length(), e < 0 ? s.length() : e).trim();
    }
}
