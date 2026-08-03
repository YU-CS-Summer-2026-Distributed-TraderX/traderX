package finos.traderx.ordermatcher.fix;

import finos.traderx.ordermatcher.auth.JwtAuthenticator;
import finos.traderx.ordermatcher.service.OrderMatcherService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import quickfix.DefaultMessageFactory;
import quickfix.FileStoreFactory;
import quickfix.SLF4JLogFactory;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.ThreadedSocketAcceptor;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * FIX acceptor lifecycle (FR-FIX01, FR-FIX11, FR-FIX13). Starts on ApplicationReadyEvent — after
 * journal replay and readiness, so a counterparty connecting during recovery is refused exactly
 * like HTTP traffic — and stops with a normal FIX logout on shutdown. Disabled entirely (no
 * socket, no ledger, no threads) when FIX_SESSION_ACCOUNTS is empty, which is also the state in
 * Spring context tests.
 *
 * <p>Session state (sequence numbers + sent messages, PersistMessages=Y) lives in a QuickFIX/J
 * file store under FIX_DATA_DIR on the order-matcher PVC, next to the {@link ClOrdIdLedger}.
 * ThreadedSocketAcceptor gives each session its own message thread, so one session's
 * ring/service backpressure never delays another session or the shared session-protocol timers
 * (ADR-034).
 */
@Component
public class FixIngress {
    private static final Logger log = LoggerFactory.getLogger(FixIngress.class);

    private final OrderMatcherService orders;
    private final JwtAuthenticator jwt;
    private final FixExecutionReportHandler reportHandler;

    private final int port;
    private final String dataDir;
    private final String serverCompId;
    private final String sessionAccountsSpec;

    private ThreadedSocketAcceptor acceptor;
    private ClOrdIdLedger ledger;

    public FixIngress(OrderMatcherService orders,
                      @Value("${auth.jwt.secret:dev-jwt-shared-secret}") String jwtSecret,
                      FixExecutionReportHandler reportHandler,
                      @Value("${fix.acceptor.port:${FIX_ACCEPTOR_PORT:18130}}") int port,
                      @Value("${fix.data.dir:${FIX_DATA_DIR:/var/lib/traderx-lmax/fix}}") String dataDir,
                      @Value("${fix.target.comp.id:${FIX_TARGET_COMP_ID:TRADERX}}") String serverCompId,
                      @Value("${fix.session.accounts:${FIX_SESSION_ACCOUNTS:}}") String sessionAccountsSpec) {
        this.orders = orders;
        // Same construction the REST service uses (JwtAuthenticator is not a bean there either);
        // one shared secret, one credential system (ADR-036).
        this.jwt = new JwtAuthenticator(jwtSecret);
        this.reportHandler = reportHandler;
        this.port = port;
        this.dataDir = dataDir;
        this.serverCompId = serverCompId;
        this.sessionAccountsSpec = sessionAccountsSpec;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        FixIdentity identity;
        try {
            identity = new FixIdentity(jwt, sessionAccountsSpec);
        } catch (IllegalArgumentException ex) {
            log.error("FIX ingress disabled — invalid FIX_SESSION_ACCOUNTS: {}", ex.getMessage());
            return;
        }
        if (identity.allowedCompIds().isEmpty()) {
            log.info("FIX ingress disabled (FIX_SESSION_ACCOUNTS is empty)");
            return;
        }
        Path base = Paths.get(dataDir);
        this.ledger = new ClOrdIdLedger(base);
        FixOrderRegistry registry = new FixOrderRegistry();
        FixOrderApplication application =
            new FixOrderApplication(orders, identity, ledger, registry, serverCompId);
        reportHandler.wire(ledger, registry, (job, ctx) -> {
            char execType = switch (job.kind()) {
                case finos.traderx.ordermatcher.lmax.OutputEvent.KIND_ORDER_CANCELED -> '4';
                default -> 'F';   // partial fill / fill are both ExecType=Trade
            };
            char ordStatus = switch (job.kind()) {
                case finos.traderx.ordermatcher.lmax.OutputEvent.KIND_ORDER_CANCELED -> '4';
                case finos.traderx.ordermatcher.lmax.OutputEvent.KIND_ORDER_FILLED -> '2';
                default -> '1';
            };
            FixMessages.sendLifecycleReport(job, ctx, execType, ordStatus);
        });
        try {
            SessionSettings settings = new SessionSettings();
            java.util.Properties defaults = new java.util.Properties();
            defaults.setProperty("ConnectionType", "acceptor");
            defaults.setProperty("SocketAcceptPort", String.valueOf(port));
            defaults.setProperty("StartTime", "00:00:00");
            defaults.setProperty("EndTime", "00:00:00");
            defaults.setProperty("FileStorePath", base.resolve("store").toString());
            defaults.setProperty("PersistMessages", "Y");
            defaults.setProperty("ResetOnLogon", "N");
            settings.set(defaults);
            for (String compId : identity.allowedCompIds()) {
                SessionID sid = new SessionID("FIX.4.4", serverCompId, compId);
                settings.setString(sid, "BeginString", "FIX.4.4");
            }
            acceptor = new ThreadedSocketAcceptor(application, new FileStoreFactory(settings),
                settings, new SLF4JLogFactory(settings), new DefaultMessageFactory());
            acceptor.start();
            log.info("FIX 4.4 acceptor listening on :{} ({} mapped CompID(s); store {})",
                port, identity.allowedCompIds().size(), base.resolve("store"));
        } catch (Exception ex) {
            log.error("FIX acceptor failed to start — FIX ingress unavailable", ex);
        }
    }

    @PreDestroy
    public void stop() {
        if (acceptor != null) {
            acceptor.stop();   // normal FIX logout to connected sessions
        }
        if (ledger != null) {
            ledger.close();
        }
    }
}
