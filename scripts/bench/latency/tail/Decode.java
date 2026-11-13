import java.io.*; import java.nio.file.*; import java.util.*; import java.util.regex.*;
/** Decode an HxProbe dir to TSV: wallMicros then every slot whose label matches REGEX. Header line names the columns. */
public class Decode {
  public static void main(String[] a) throws Exception {
    Path d = Path.of(a[0]); Pattern want = Pattern.compile(a[1]);
    List<String> reg = Files.readAllLines(d.resolve("counters.reg"));
    Matcher m0 = Pattern.compile("wallMicros=(\\d+)").matcher(reg.get(0)); m0.find(); long wall = Long.parseLong(m0.group(1));
    TreeMap<Integer,String> keep = new TreeMap<>();
    Pattern sp = Pattern.compile("SLOT (\\d+) (-?\\d+) (-?\\d+) (.*?)(?: atMicros=\\d+)?$");
    for (String l : reg) { Matcher m = sp.matcher(l); if (m.matches() && want.matcher(m.group(3) + " " + m.group(4)).find()) keep.put(Integer.parseInt(m.group(1)), "t" + m.group(3) + ":" + m.group(4).replace('\t', ' ')); }
    PrintStream o = new PrintStream(new BufferedOutputStream(new FileOutputStream(a[2]), 1 << 20));
    StringBuilder h = new StringBuilder("wall"); for (String v : keep.values()) h.append('\t').append(v); o.println(h);
    int[] ks = keep.keySet().stream().mapToInt(Integer::intValue).toArray();
    long[] prev = new long[4096];
    try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(d.resolve("counters.bin").toFile()), 1 << 22))) {
      StringBuilder sb = new StringBuilder();
      while (true) {
        long dt; try { dt = rv(in); } catch (EOFException e) { break; }
        wall += dt; int n;
        try { n = (int) rv(in); for (int i = 0; i < n; i++) { long z = rv(in); prev[i] += (z >>> 1) ^ -(z & 1); } } catch (EOFException e) { break; }
        sb.setLength(0); sb.append(wall); for (int k : ks) sb.append('\t').append(k < n ? prev[k] : 0); o.println(sb);
      }
    }
    o.close();
  }
  static long rv(DataInputStream in) throws IOException { long v = 0; int s = 0; while (true) { int x = in.read(); if (x < 0) throw new EOFException(); v |= (long) (x & 0x7F) << s; if ((x & 0x80) == 0) return v; s += 7; } }
}
