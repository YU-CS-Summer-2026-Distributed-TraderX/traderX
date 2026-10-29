package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.OutputEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-095 (FR-FTL-01..03): the external CompID logs on over TLS on its own port and nowhere else; the plaintext port
 * keeps its CompIDs; a plaintext connection to the TLS port gets no FIX answer; TLS alone is not a logon. Raw
 * sockets read the acceptor's answer off the wire, so "refused" means no Logon(A) came back.
 */
@Timeout(60)
class FixGatewayTlsTest {
    private static final String PW = "ext-secret";
    @TempDir
    static Path dir;
    private static Path keystore;
    private static SSLContext client;
    private FixGatewayAcceptor acceptor;
    private int plainPort;
    private int tlsPort;

    @BeforeAll
    static void certificate() throws Exception {
        keystore = dir.resolve("fix-tls.p12");
        final Path cert = dir.resolve("fix-tls.crt");
        final Path trust = dir.resolve("trust.p12");
        final String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        run(keytool, "-genkeypair", "-alias", "fix", "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "2",
            "-dname", "CN=fix.yaakovseif.dev", "-ext", "SAN=dns:fix.yaakovseif.dev",
            "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", "kspass");
        run(keytool, "-exportcert", "-rfc", "-alias", "fix", "-keystore", keystore.toString(), "-storepass", "kspass",
            "-file", cert.toString());
        run(keytool, "-importcert", "-noprompt", "-alias", "fix", "-file", cert.toString(),
            "-storetype", "PKCS12", "-keystore", trust.toString(), "-storepass", "trustpass");
        final KeyStore ts = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(trust)) {
            ts.load(in, "trustpass".toCharArray());
        }
        final TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
        tmf.init(ts);
        client = SSLContext.getInstance("TLS");
        client.init(null, tmf.getTrustManagers(), null);
    }

    private static void run(final String... cmd) throws Exception {
        final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        final String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), String.join(" ", cmd) + "\n" + out);
    }

    @AfterEach
    void tearDown() {
        if (acceptor != null) {
            acceptor.stop();
        }
    }

    private void start() throws Exception {
        plainPort = freePort();
        tlsPort = freePort();
        acceptor = new FixGatewayAcceptor(new Venue(), null, plainPort, "TRADERX", 11, List.of("CLIENT1"),
            FixSessionAuth.fromEnv("CLIENT1:c1pw:*,EXTQFJ:" + PW + ":22214|42422"))
            .withTls(tlsPort, List.of("EXTQFJ"), keystore.toString(), "kspass");
        acceptor.start();
    }

    @Test
    void theExternalCompIdLogsOnOverTlsOnly() throws Exception {
        start();
        assertTrue(loggedOn(tls(), "EXTQFJ", PW), "EXTQFJ over TLS with its password: Logon(A)");
        assertFalse(loggedOn(new Socket("127.0.0.1", plainPort), "EXTQFJ", PW), "EXTQFJ on the plaintext port");
        assertFalse(loggedOn(tls(), "EXTQFJ", "wrong"), "TLS is not auth: a wrong password is refused");
        assertFalse(loggedOn(tls(), "CLIENT1", "c1pw"), "a plaintext-port CompID on the TLS port");
        assertTrue(loggedOn(new Socket("127.0.0.1", plainPort), "CLIENT1", "c1pw"), "control: plaintext CLIENT1");
    }

    @Test
    void plaintextToTheTlsPortGetsNoFixAnswer() throws Exception {
        start();
        try (Socket s = new Socket("127.0.0.1", tlsPort)) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(logon("EXTQFJ", PW));
            final String answer = readAnswer(s);
            assertFalse(answer.contains("8=FIX"), "plaintext FIX on the TLS port was answered in FIX: " + answer);
        }
    }

    @Test
    void aTlsPortWithoutAKeystoreRefusesToStart() {
        final FixGatewayAcceptor a = new FixGatewayAcceptor(new Venue(), null, 1, "TRADERX", 11, List.of("CLIENT1"));
        assertThrows(IllegalStateException.class, () -> a.withTls(2, List.of("EXTQFJ"), "", ""));
        assertThrows(IllegalStateException.class, () -> a.withTls(2, List.of("CLIENT1"), keystore.toString(), "kspass"));
        assertThrows(IllegalStateException.class, () -> a.withTls(2, List.of(""), keystore.toString(), "kspass"));
    }

    private SSLSocket tls() throws IOException {
        final SSLSocket s = (SSLSocket) client.getSocketFactory().createSocket("127.0.0.1", tlsPort);
        s.setSoTimeout(5000);   // a blocked socket read ignores @Timeout's interrupt
        s.startHandshake();   // the pinned certificate: a handshake failure fails the test here, not as a refusal
        return s;
    }

    private static boolean loggedOn(final Socket s, final String comp, final String password) throws IOException {
        try (s) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(logon(comp, password));
            return readAnswer(s).contains("\u000135=A\u0001");
        }
    }

    /** Everything the acceptor sends until it closes, its first complete message, or 5 s of silence. */
    private static String readAnswer(final Socket s) throws IOException {
        final ByteArrayOutputStream got = new ByteArrayOutputStream();
        final byte[] buf = new byte[4096];
        try {
            for (int r; (r = s.getInputStream().read(buf)) > 0; ) {
                got.write(buf, 0, r);
                if (got.toString(StandardCharsets.US_ASCII).matches("(?s).*\u000110=\\d{3}\u0001")) {
                    break;
                }
            }
        } catch (final SocketTimeoutException | javax.net.ssl.SSLException | java.net.SocketException closed) {
            // silence, a reset or an alert: no answer
        }
        return got.toString(StandardCharsets.US_ASCII);
    }

    private static byte[] logon(final String comp, final String password) {
        final StringBuilder body = new StringBuilder("35=A\u000149=").append(comp)
            .append("\u000156=TRADERX\u000134=1\u000152=")
            .append(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS")
                .format(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)))
            .append("\u000198=0\u0001108=30\u0001141=Y\u0001554=").append(password).append('\u0001');
        final String head = "8=FIX.4.4\u00019=" + body.length() + "\u0001";
        int sum = 0;
        for (final char c : (head + body).toCharArray()) {
            sum += c;
        }
        return (head + body + "10=" + String.format("%03d", sum % 256) + "\u0001").getBytes(StandardCharsets.US_ASCII);
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static final class Venue implements OrderSubmitter {
        @Override
        public ExecResult submitOrder(final String clOrdId, final int accountId, final String ticker,
                                      final char side, final int qty, final long limitPxTicks) {
            return new ExecResult(true, 1, OutputEvent.KIND_ORDER_ACCEPTED);
        }

        @Override
        public ExecResult submitCancel(final int orderRef) {
            return new ExecResult(true, orderRef, OutputEvent.KIND_ORDER_CANCELED);
        }
    }
}
