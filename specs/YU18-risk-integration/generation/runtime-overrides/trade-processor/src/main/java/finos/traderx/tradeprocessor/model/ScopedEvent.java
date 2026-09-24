package finos.traderx.tradeprocessor.model;
public interface ScopedEvent {
 String getProjectionScope(); String getClusterEpoch(); String getEventIdScheme();
 String getRunDescriptorHash(); Long getConsensusSequence();
}
