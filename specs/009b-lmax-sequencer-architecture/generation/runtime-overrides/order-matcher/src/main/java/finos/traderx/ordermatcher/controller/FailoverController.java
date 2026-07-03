package finos.traderx.ordermatcher.controller;

import finos.traderx.ordermatcher.lmax.LmaxEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Failover surface (state 009b warm standby, FR-09B30..B32).
 *
 * <p>{@code GET /admin/ready} is the VIP's health check: 200 only on the LIVE node, so the
 * haproxy in front routes commands to the leader and fails over the moment a promoted standby
 * reports ready. {@code /admin/role} is the human/ops view. {@code POST /admin/promote} forces a
 * manual promotion (the watchdog normally does this) — it still has to win the leader lock, so it
 * cannot split-brain a healthy primary.
 */
@RestController
@RequestMapping("/admin")
public class FailoverController {
    private final LmaxEngine engine;

    public FailoverController(LmaxEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> body = status();
        return engine.isLive() ? ResponseEntity.ok(body) : ResponseEntity.status(503).body(body);
    }

    @GetMapping("/role")
    public Map<String, Object> role() {
        return status();
    }

    @PostMapping("/promote")
    public ResponseEntity<Map<String, Object>> promote() {
        boolean promoted = engine.promote();
        Map<String, Object> body = status();
        body.put("promoted", promoted);
        return promoted ? ResponseEntity.ok(body) : ResponseEntity.status(409).body(body);
    }

    private Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("role", engine.role());
        body.put("live", engine.isLive());
        body.put("leaderLockHeld", engine.leaderLockHeld());
        body.put("journalWriting", engine.journalWriting());
        body.put("followerLagBytes", engine.followerLagBytes());
        body.put("followerAppliedEvents", engine.followerAppliedEvents());
        body.put("promotions", engine.promotions());
        return body;
    }
}
