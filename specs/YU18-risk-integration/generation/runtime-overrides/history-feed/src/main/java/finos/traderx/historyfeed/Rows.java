package finos.traderx.historyfeed;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * Column buffers for one store write, and the parser that fills them from the bridges' own JSON
 * ({@code TradeNatsPublisher.encode}, {@code OrderNatsPublisher.encode}): flat, no escapes, nothing nested below
 * {@code payload}; unknown keys are skipped, so an additive bridge field never fails a row.
 *
 * <p>Trades: key = tradeSeq, ref = source order ref. Orders: key = order ref, ref = client order key (0 = none).
 */
final class Rows {
  final boolean trades;
  int n;
  final long[] ts, seq, key, ref, cseq, created;
  final int[] acct, qty, remaining, lastQty, ordinal;
  final byte[] side;
  final double[] px, lastPx;
  final String[] sec, status;

  Rows(boolean trades, int cap) {
    this.trades = trades;
    ts = new long[cap]; seq = new long[cap]; key = new long[cap]; ref = new long[cap]; cseq = new long[cap];
    acct = new int[cap]; qty = new int[cap]; side = new byte[cap]; px = new double[cap]; sec = new String[cap];
    created = new long[cap]; remaining = new int[cap]; lastQty = new int[cap]; ordinal = new int[cap];
    lastPx = new double[cap]; status = new String[cap];
  }

  long lastSeq() { return seq[n - 1]; }

  /** Parses one message into row n; the stream sequence is the row's identity (FR-HST-01). */
  static final class Parser {
    private final HashMap<String, String> interned = new HashMap<>();
    private byte[] d;
    private int i;

    void parse(byte[] data, Rows b, long streamSeq) {
      d = data; i = 0;
      int r = b.n;
      b.seq[r] = streamSeq; b.ref[r] = 0; b.created[r] = 0; b.remaining[r] = 0; b.lastQty[r] = 0;
      b.ordinal[r] = 0; b.lastPx[r] = 0; b.status[r] = "";
      while (true) {
        int ks = indexOf('"', i);
        if (ks < 0) break;
        int ke = indexOf('"', ks + 1);
        String k = new String(d, ks + 1, ke - ks - 1, StandardCharsets.ISO_8859_1);
        i = ke + 2;                                   // past ":
        if (k.equals("payload")) { i++; continue; }   // past {
        switch (k) {
          case "date" -> b.ts[r] = num();
          case "id" -> {
            String s = str(); int dash = s.lastIndexOf('-');
            if (b.trades) {                           // [e1-<epoch>-]<tradeSeq>-B|S
              int d2 = s.lastIndexOf('-', dash - 1);
              b.key[r] = Long.parseLong(s, d2 + 1, dash, 10);
              b.side[r] = (byte) s.charAt(dash + 1);
            } else b.key[r] = Long.parseLong(s, dash + 1, s.length(), 10);   // <epoch>-<ref>
          }
          case "security" -> b.sec[r] = intern(str());
          case "quantity" -> b.qty[r] = (int) num();
          case "price", "limitPrice" -> b.px[r] = px();
          case "accountId" -> b.acct[r] = (int) num();
          case "side" -> { String s = str(); if (!b.trades) b.side[r] = (byte) s.charAt(0); }
          case "sourceOrderId" -> { String s = str(); b.ref[r] = Long.parseLong(s, s.lastIndexOf('-') + 1, s.length(), 10); }
          case "consensusSequence" -> b.cseq[r] = num();
          case "remainingQuantity" -> b.remaining[r] = (int) num();
          case "status" -> b.status[r] = intern(str());
          case "lastExecutionPrice" -> b.lastPx[r] = px();
          case "lastFillQuantity" -> b.lastQty[r] = (int) num();
          case "createdAt" -> b.created[r] = num();
          case "updatedAt" -> b.ts[r] = num();
          case "outputOrdinal" -> b.ordinal[r] = (int) num();
          case "clientOrderKey" -> b.ref[r] = Long.parseUnsignedLong(str());
          default -> skip();
        }
      }
      if (b.trades && b.side[r] != 'B' && b.side[r] != 'S') throw new IllegalArgumentException("trade id without -B/-S");
      b.n++;
    }

    private int indexOf(char c, int from) { for (int j = from; j < d.length; j++) if (d[j] == c) return j; return -1; }
    private String intern(String s) { return interned.computeIfAbsent(s, x -> x); }
    private String str() { int s = i + 1, e = indexOf('"', s); i = e + 1; return new String(d, s, e - s, StandardCharsets.ISO_8859_1); }
    private long num() {
      long v = 0; boolean neg = d[i] == '-'; if (neg) i++;
      while (i < d.length && d[i] >= '0' && d[i] <= '9') v = v * 10 + (d[i++] - '0');
      return neg ? -v : v;
    }
    private double px() {
      if (d[i] == 'n') { i += 4; return 0; }       // null
      long w = num(); if (i >= d.length || d[i] != '.') return w;
      i++; long f = 0, sc = 1;
      while (d[i] >= '0' && d[i] <= '9') { f = f * 10 + (d[i++] - '0'); sc *= 10; }
      return w + (double) f / sc;
    }
    private void skip() { if (d[i] == '"') str(); else while (i < d.length && d[i] != ',' && d[i] != '}') i++; }
  }
}
