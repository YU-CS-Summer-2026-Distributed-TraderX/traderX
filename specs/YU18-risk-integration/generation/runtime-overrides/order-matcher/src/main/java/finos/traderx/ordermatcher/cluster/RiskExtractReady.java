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
        if (witness != Math.addExact(stamp.consensusSequence(), 1)) {
            throw new IllegalArgumentException("extract is not quiescent");
        }
        return new JSONObject()
            .put("schema", RiskExtractCsv.SCHEMA).put("uri", positionsUri)
            .put("consensusSequence", stamp.consensusSequence())
            .put("sessionDate", stamp.sessionDate().toString())
            .put("priceSnapshotVersion", stamp.priceVersion())
            .put("rows", positions.lines().filter(l -> !l.startsWith("#")).count() - 1)
            .put("sha256", RiskExtractCut.sha256(positions)).put("cutSha256", stamp.cutSha256())
            .put("quiesceWitnessSequence", witness).put("contractsSchema", SwapContractCsv.SCHEMA)
            .put("contractsUri", contractsUri)
            .put("contracts", contracts.lines().filter(l -> !l.startsWith("#")).count() - 1)
            .put("contractsSha256", RiskExtractCut.sha256(contracts)).toString();
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
