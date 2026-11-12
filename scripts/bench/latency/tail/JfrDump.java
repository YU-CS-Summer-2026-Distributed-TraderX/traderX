import jdk.jfr.consumer.*; import java.nio.file.*; import java.io.*; import java.util.*;
/** async-profiler JFR -> TSV: epochMicros, thread, frames (leaf first, up to DEPTH) for one thread-name substring. */
public class JfrDump {
  public static void main(String[] a) throws Exception {
    String want = a[1]; int depth = Integer.parseInt(a[2]);
    try (RecordingFile f = new RecordingFile(Path.of(a[0])); PrintStream o = new PrintStream(new BufferedOutputStream(new FileOutputStream(a[3]), 1 << 20))) {
      while (f.hasMoreEvents()) {
        RecordedEvent e = f.readEvent();
        if (!e.getEventType().getName().equals("jdk.ExecutionSample")) continue;
        RecordedThread t = e.getThread("sampledThread"); String n = t == null ? "?" : String.valueOf(t.getJavaName() != null ? t.getJavaName() : t.getOSName());
        if (!n.contains(want)) continue;
        RecordedStackTrace st = e.getStackTrace(); StringBuilder sb = new StringBuilder();
        if (st != null) { int i = 0; for (RecordedFrame fr : st.getFrames()) { if (i++ >= depth) break; String c = fr.getMethod().getType() == null ? "" : fr.getMethod().getType().getName(); sb.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(fr.getMethod().getName()).append(';'); } }
        java.time.Instant s = e.getStartTime(); o.println((s.getEpochSecond() * 1_000_000L + s.getNano() / 1000) + "\t" + n + "\t" + sb);
      }
    }
  }
}
