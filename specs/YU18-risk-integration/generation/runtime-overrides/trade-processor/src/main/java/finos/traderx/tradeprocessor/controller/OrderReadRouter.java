package finos.traderx.tradeprocessor.controller;

import finos.traderx.messaging.Subscriber;
import finos.traderx.tradeprocessor.OrderFeedHandler;
import finos.traderx.tradeprocessor.service.PartitionOwnership;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * FR-DRS-46: the order view's read router. With N replicas each replica's in-memory order view holds only its own
 * partitions, and {@code GET /accounts/{id}/orders} (open, {@code ?status=all}, {@code ?clientOrderKey=}) is the one read
 * served from it. Clients keep calling the {@code trade-processor} Service, so the contract is unchanged: whichever
 * replica receives the read answers it if it owns the account's partition ({@code accountId mod P}), and otherwise
 * forwards it, path and query and Authorization as received, to the live owner {@code projection_partition_owner} names,
 * at {@code PROJECTION_REPLICA_URL} with {@code {replica}} replaced by the owner's id (the pod name), and returns the
 * owner's status and body. A forwarded read carries {@code X-TraderX-Routed} and is never forwarded again: a replica
 * that receives one for a partition it does not own answers 421 (OrderController). No live owner, or an owner that
 * cannot be reached, is 503 naming the partition, never an empty list. Unset URL (one replica) = no routing.
 */
@Component
public class OrderReadRouter extends OncePerRequestFilter {
  static final String ROUTED = "X-TraderX-Routed";
  private static final Pattern PATH = Pattern.compile("^/accounts/(-?\\d+)/orders$");
  private static final long OWNERS_MS = 2000;   // owners re-read at most this often, and at once after a failed forward

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final AtomicLong local = new AtomicLong(), forwarded = new AtomicLong(), failed = new AtomicLong();
  private volatile Map<Integer, String> owners = Map.of();
  private volatile long ownersAt;

  @Autowired(required = false) private PartitionOwnership ownership;
  @Autowired(required = false) private List<Subscriber<?>> subscribers;
  @Value("${traderx.feed.replica-url:}") private String replicaUrl = "";

  @Override
  protected boolean shouldNotFilter(HttpServletRequest r) {
    return ownership == null || replicaUrl.isBlank() || !"GET".equals(r.getMethod()) || !PATH.matcher(r.getRequestURI()).matches();
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    Matcher m = PATH.matcher(req.getRequestURI());
    int account;
    try { m.matches(); account = Integer.parseInt(m.group(1)); } catch (RuntimeException notAnAccount) { chain.doFilter(req, res); return; }
    if (ownership.ownsAccount(account) || req.getHeader(ROUTED) != null || !memoryView()) {
      if (ownership.ownsAccount(account)) { local.incrementAndGet(); }
      chain.doFilter(req, res);   // owned, already routed once (OrderController answers 421), or SQL (any replica)
      return;
    }
    int p = Math.floorMod(account, ownership.partitions());
    String owner = owner(p, false);
    for (int attempt = 0; attempt < 2 && !owner.isEmpty(); attempt++) {
      try {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(replicaUrl.replace("{replica}", owner) + req.getRequestURI()
            + (req.getQueryString() == null ? "" : "?" + req.getQueryString()))).timeout(Duration.ofSeconds(10))
            .header(ROUTED, ownership.replica()).GET();
        if (req.getHeader("Authorization") != null) { b.header("Authorization", req.getHeader("Authorization")); }
        HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() == 421) { throw new IOException("421 from " + owner); }   // it no longer owns p
        forwarded.incrementAndGet();
        res.setStatus(r.statusCode());
        r.headers().firstValue("Content-Type").ifPresent(res::setContentType);
        res.getOutputStream().write(r.body());
        return;
      } catch (InterruptedException x) {
        Thread.currentThread().interrupt();
        break;
      } catch (IOException x) {
        String again = owner(p, true);
        if (again.equals(owner)) { break; }
        owner = again;
      }
    }
    failed.incrementAndGet();
    res.setStatus(503);
    res.setContentType("text/plain");
    res.getWriter().write("account " + account + " is partition " + p + ", whose owner ("
        + (owner.isEmpty() ? "none live" : owner) + ") could not be reached from replica " + ownership.replica());
  }

  private boolean memoryView() {
    if (subscribers != null) { for (Subscriber<?> s : subscribers) { if (s instanceof OrderFeedHandler o && !o.persists()) { return true; } } }
    return false;
  }

  private String owner(int partition, boolean refresh) {
    long now = System.currentTimeMillis();
    if (refresh || now - ownersAt > OWNERS_MS) {
      try { owners = ownership.liveOwners(); ownersAt = now; } catch (RuntimeException x) { /* keep the last map */ }
    }
    return owners.getOrDefault(partition, "");
  }

  /** For /v2/projection-feed: reads answered here, forwarded to their owner, and refused (503). */
  public Map<String, Long> counts() { return Map.of("local", local.get(), "forwarded", forwarded.get(), "failed", failed.get()); }
}
