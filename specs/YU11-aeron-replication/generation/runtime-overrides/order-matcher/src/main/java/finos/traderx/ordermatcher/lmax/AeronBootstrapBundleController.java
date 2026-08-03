package finos.traderx.ordermatcher.lmax;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;

/** Peer-only, HMAC-authenticated bootstrap transport. Never used by the replication hot path. */
@RestController
@ConditionalOnProperty(prefix = "blp.replication", name = "enabled", havingValue = "true")
public final class AeronBootstrapBundleController {
    public static final String PATH = "/internal/aeron/bootstrap-bundle";
    public static final String PEER_HEADER = "X-YU11-Peer";
    public static final String AUTH_HEADER = "X-YU11-Auth";

    private final LmaxEngine engine;
    private final byte[] secret;

    public AeronBootstrapBundleController(
        LmaxEngine engine,
        @Value("${blp.replication.secret-file:}") String secretFile,
        @Value("${blp.replication.secret:}") String inlineSecret
    ) {
        this.engine = engine;
        this.secret = AeronPeerAuthenticator.loadSecret(secretFile, inlineSecret);
    }

    @GetMapping(path = PATH, produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<?> bundle(
        @RequestParam long epoch,
        @RequestParam long correlation,
        @RequestParam long issuedAt,
        @RequestHeader(PEER_HEADER) String peerId,
        @RequestHeader(AUTH_HEADER) String authTag
    ) {
        if (!peerId.equals(engine.expectedAeronPeerId())
            || !AeronBootstrapAuth.verifyRequest(secret, peerId, epoch, correlation,
                issuedAt, authTag, System.currentTimeMillis())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!engine.isCurrentAeronPrimary(epoch)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        try {
            Path file = engine.createAeronBootstrapBundle(epoch, correlation, secret);
            FileSystemResource resource = new FileSystemResource(file);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + file.getFileName() + "\"")
                .contentLength(resource.contentLength())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ex.getMessage());
        }
    }
}
