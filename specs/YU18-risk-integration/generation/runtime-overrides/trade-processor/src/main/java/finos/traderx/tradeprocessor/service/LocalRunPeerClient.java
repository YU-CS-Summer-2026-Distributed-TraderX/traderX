package finos.traderx.tradeprocessor.service;

import com.fasterxml.jackson.databind.*;
import finos.traderx.tradeprocessor.auth.JwtTokenMinter;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Explicit local maintenance only. Never follows redirects or reaches a remote/cloud host. */
@Component
public final class LocalRunPeerClient implements RunPeerClient {
    private final String token,authorization;
    private final ObjectMapper mapper=new ObjectMapper();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public LocalRunPeerClient(@Value("${RISK_CONTROL_TOKEN:dev-risk-control}") String token,
                              @Value("${auth.jwt.secret:dev-jwt-shared-secret}") String secret) {
        this.token=token;this.authorization="Bearer "+new JwtTokenMinter(secret).mint("local-run-migration",Set.of(),true,0L);
    }
    private JsonNode request(String endpoint,String path,JsonNode body) {
        URI base=URI.create(endpoint);
        if(!"http".equals(base.getScheme()) || !Set.of("localhost","127.0.0.1","[::1]").contains(base.getHost())
            || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null
            || !(base.getPath().isEmpty() || "/".equals(base.getPath()))) {
            throw new IllegalArgumentException("RUN_LOCAL_ENDPOINT_REQUIRED");
        }
        try {
            var b=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofMinutes(2))
                .header("Authorization",authorization).header("X-Risk-Control-Token",token)
                .header("X-Risk-Operator","local-run-migration");
            if(body==null) b.GET();else b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            var response=http.send(b.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) throw new IllegalStateException("RUN_PEER_REFUSED: HTTP "+response.statusCode());
            return mapper.readTree(response.body());
        } catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new IllegalStateException("RUN_PEER_INTERRUPTED",ex);}
        catch(java.io.IOException ex) {throw new IllegalStateException("RUN_PEER_UNAVAILABLE",ex);}
    }
    public JsonNode status(String endpoint) {return request(endpoint,"/run/status",null);}
    public void control(String endpoint,String hash,String operation) {
        request(endpoint,"/run/control",mapper.createObjectNode().put("descriptorHash",hash).put("operation",operation));
    }
    public JsonNode projectionEvents(String endpoint) {return request(endpoint,"/recon/projection-events",null);}
}
