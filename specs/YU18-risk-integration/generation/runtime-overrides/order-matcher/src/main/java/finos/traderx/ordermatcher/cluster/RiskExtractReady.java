package finos.traderx.ordermatcher.cluster;

import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Completion payload shared by the producer and local exporter demonstration. */
final class RiskExtractReady {
    static String payload(RiskExtractCsv.Stamp stamp, long witness, String positions,
                          String contracts, String positionsUri, String contractsUri) {
        return payload(stamp,witness,positions,contracts,positionsUri,contractsUri,null);
    }
    static String payload(RiskExtractCsv.Stamp stamp,long witness,String positions,String contracts,
                          String positionsUri,String contractsUri,RunDescriptor descriptor) {
        if (witness != Math.addExact(stamp.consensusSequence(), 1)) {
            throw new IllegalArgumentException("extract is not quiescent");
        }
        JSONObject result = new JSONObject()
            .put("schema", RiskExtractCsv.SCHEMA).put("uri", positionsUri)
            .put("consensusSequence", stamp.consensusSequence())
            .put("sessionDate", stamp.sessionDate().toString())
            .put("priceSnapshotVersion", stamp.priceVersion())
            .put("rows", positions.lines().filter(l -> !l.startsWith("#")).count() - 1)
            .put("sha256", RiskExtractCut.sha256(positions)).put("cutSha256", stamp.cutSha256())
            .put("quiesceWitnessSequence", witness).put("contractsSchema", SwapContractCsv.SCHEMA)
            .put("contractsUri", contractsUri)
            .put("contracts", contracts.lines().filter(l -> !l.startsWith("#")).count() - 1)
            .put("contractsSha256", RiskExtractCut.sha256(contracts));
        if(descriptor!=null) {
            if(!positionsUri.endsWith(".csv")) {throw new IllegalArgumentException("RUN_CUT_URI_UNAVAILABLE");}
            result.put("receiptSchema","traderx.risk-extract.ready.v2");
            result.put("cutUri",positionsUri.substring(0,positionsUri.length()-4)+".cut");
            result.put("platformIdentity",new JSONObject().put("runDescriptorSha256",descriptor.hash())
                .put("epoch",descriptor.epoch()).put("eventIdScheme",descriptor.scheme())
                .put("projectionScope",descriptor.projectionScope()).put("storageLineage",descriptor.storageLineage()));
        }
        return result.toString();
    }

    /** The descriptor hash is part of the consensus cut bytes, hence of the CSV cut hash. */
    static RunDescriptor descriptorForCut(String cut,String configuredPath) throws Exception {
        String head=cut.substring(0,cut.indexOf('\n'));
        java.util.Map<String,String> fields=new java.util.HashMap<>();
        for(String token:head.split(" ")) {
            int eq=token.indexOf('=');if(eq>0) {
                if(fields.put(token.substring(0,eq),token.substring(eq+1))!=null) {
                    throw new IllegalArgumentException("RUN_CUT_DUPLICATE_FIELD");
                }
            }
        }
        String hash=fields.get("runDescriptorHash");
        RunDescriptor descriptor=configuredPath==null || configuredPath.isBlank()?null:RunDescriptor.read(Path.of(configuredPath));
        if(hash==null) {
            if(descriptor!=null && descriptor.managedIds()) {throw new IllegalArgumentException("RUN_CUT_IDENTITY_MISSING");}
            return null; // explicitly attributed legacy receipt path remains unchanged
        }
        if(descriptor==null || !descriptor.managedIds() || !hash.equals(descriptor.hash())
            || !descriptor.projectionScope().equals(fields.get("projectionScope"))
            || !descriptor.scheme().equals(fields.get("eventIdScheme"))) {
            throw new IllegalArgumentException("RUN_CUT_DESCRIPTOR_MISMATCH");
        }
        return descriptor;
    }

    static Path publish(Path directory, RiskExtractCsv.Stamp stamp, String payload) throws IOException {
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (Files.isSymbolicLink(directory)
            || !Files.getPosixFilePermissions(directory).equals(PosixFilePermissions.fromString("rwx------"))) {
            throw new IOException("receipt directory must be private (0700), not a symlink");
        }
        Path destination = directory.resolve(stamp.sessionDate() + "-v" + stamp.priceVersion()
            + "-seq-" + stamp.consensusSequence() + ".ready.json");
        byte[] bytes = (payload + "\n").getBytes(StandardCharsets.UTF_8);
        if (Files.exists(destination)) {
            if (Files.isSymbolicLink(destination) || !java.util.Arrays.equals(Files.readAllBytes(destination), bytes)) {
                throw new IOException("receipt identity already exists with different bytes");
            }
            return destination;
        }
        Path staging = Files.createTempFile(directory, ".ready-stage-", ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.write(staging, bytes);
            // Hard-link publication is atomic and fails if destination exists; never replaces it.
            Files.createLink(destination, staging);
        } finally {
            Files.deleteIfExists(staging);
        }
        return destination;
    }
}
