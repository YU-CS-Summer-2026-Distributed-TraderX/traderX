package finos.traderx.tradeprocessor.service;

import com.fasterxml.jackson.databind.JsonNode;

/** Local migration control dependency; tests can interrupt individual durable boundaries. */
public interface RunPeerClient {
    JsonNode status(String endpoint);
    void control(String endpoint,String descriptorHash,String operation);
    JsonNode projectionEvents(String endpoint);
}
