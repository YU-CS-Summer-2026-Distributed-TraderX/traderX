package finos.traderx.ordermatcher.cluster;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Explicit immutable identity bound to retained storage. Loading never provisions or invents it. */
public final class RunDescriptor {
 public static final String FILE="run-identity.json";
 private final String epoch,scheme,storageLineage,projectionScope,hash,adoptionHash;
 private final long[] words;
 private RunDescriptor(String epoch,String scheme,String lineage,String scope,String hash,String adoptionHash) {
  this.adoptionHash=adoptionHash;
  this.epoch=epoch;this.scheme=scheme;this.storageLineage=lineage;this.projectionScope=scope;this.hash=hash;
  byte[] bytes=HexFormat.of().parseHex(hash);ByteBuffer b=ByteBuffer.wrap(bytes);
  words=new long[]{b.getLong(),b.getLong(),b.getLong(),b.getLong()};
 }
 public static RunDescriptor read(Path path) throws Exception {
  if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>16384) {
   throw new IllegalArgumentException("RUN_DESCRIPTOR_FILE_INVALID: "+path);
  }
  byte[] raw=Files.readAllBytes(path);
  var mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
      .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  JsonNode n=mapper.readTree(raw);Set<String> fields=new HashSet<>();n.fieldNames().forEachRemaining(fields::add);
  if (!fields.equals(Set.of("schema","epoch","eventIdScheme","storageLineage","projectionScope","adoptionEvidenceSha256"))
      || !"traderx.run.v1".equals(text(n,"schema"))) {throw new IllegalArgumentException("RUN_DESCRIPTOR_SCHEMA");}
  String epoch=text(n,"epoch"),scheme=text(n,"eventIdScheme"),lineage=text(n,"storageLineage"),scope=text(n,"projectionScope");
  if (epoch.isBlank() || !lineage.matches("[a-z0-9_-]{1,64}") || !scope.matches("[a-z0-9_-]{1,64}")) {
   throw new IllegalArgumentException("RUN_DESCRIPTOR_IDENTITY");
  }
  if ("epoch-v1".equals(scheme)) {
   if (!epoch.matches("[a-z0-9_]{1,25}") || "legacy-unknown".equals(scope)
       || !n.get("adoptionEvidenceSha256").isNull()) {throw new IllegalArgumentException("RUN_V1_DESCRIPTOR_INVALID");}
  } else if ("legacy-v0".equals(scheme)) {
   if (!"legacy-unknown".equals(scope) || !text(n,"adoptionEvidenceSha256").matches("[0-9a-f]{64}")) {
    throw new IllegalArgumentException("RUN_LEGACY_ADOPTION_REQUIRES_EVIDENCE");
   }
  } else {throw new IllegalArgumentException("RUN_ID_SCHEME_UNKNOWN");}
  String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
  return new RunDescriptor(epoch,scheme,lineage,scope,hash,n.get("adoptionEvidenceSha256").isNull()?null:text(n,"adoptionEvidenceSha256"));
 }
 private static String text(JsonNode node,String name) {
  JsonNode value=node.get(name);
  if (value==null || !value.isTextual()) {throw new IllegalArgumentException("RUN_DESCRIPTOR_FIELD: "+name);}
  return value.textValue();
 }
 public static RunDescriptor load(Path base, String expectedPath,String configuredEpoch) throws Exception {
  Path persisted=base.resolve(FILE);
  if (!Files.exists(persisted,LinkOption.NOFOLLOW_LINKS)) {
   if (expectedPath!=null && !expectedPath.isBlank()) {
    throw new IllegalStateException("RUN_DESCRIPTOR_NOT_PROVISIONED: explicit fresh provisioning or evidenced legacy adoption required");
   }
   return null; // Existing unconfigured legacy runtime remains compatible; no managed safety claim.
  }
  RunDescriptor value=read(persisted);
  if(value.adoptionHash!=null) {
   Path evidence=base.resolve("run-adoption-evidence.json");
   if(!Files.isRegularFile(evidence,LinkOption.NOFOLLOW_LINKS)
       || !value.adoptionHash.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(evidence))))) {
    throw new IllegalStateException("RUN_LEGACY_ADOPTION_EVIDENCE_MISMATCH");
   }
  }
  if (expectedPath!=null && !expectedPath.isBlank() && !value.hash.equals(read(Path.of(expectedPath)).hash)) {
   throw new IllegalStateException("RUN_STORAGE_DESCRIPTOR_MISMATCH");
  }
  if (configuredEpoch!=null && !configuredEpoch.isBlank() && !configuredEpoch.equals(value.epoch)) {
   throw new IllegalStateException("RUN_EPOCH_ENV_MISMATCH");
  }
  return value;
 }
 public String epoch(){return epoch;} public String scheme(){return scheme;}
 public String storageLineage(){return storageLineage;} public String projectionScope(){return projectionScope;}
 public String hash(){return hash;} public long word(int index){return words[index];}
 public boolean managedIds(){return "epoch-v1".equals(scheme);}
 public String tradeId(long sequence,byte side) {
  if (sequence<=0 || (side!=0 && side!=1)) {throw new IllegalArgumentException("RUN_TRADE_KEY_INVALID");}
  return (managedIds()?"e1-"+epoch+"-":"")+sequence+(side==0?"-B":"-S");
 }
 public String orderId(long reference){return epoch+"-"+reference;}
 public String eventFields(long sequence) {
  if (!managedIds()) {return "";} // Adopted legacy replay keeps original wire and SQL attribution.
  return ",\"projectionScope\":\""+projectionScope+"\",\"clusterEpoch\":\""+epoch
      +"\",\"eventIdScheme\":\""+scheme+"\",\"runDescriptorHash\":\""+hash
      +"\",\"consensusSequence\":"+sequence;
 }

 public void control(finos.traderx.ordermatcher.lmax.InputEvent e,byte operation) {
  e.type=finos.traderx.ordermatcher.lmax.InputEvent.TYPE_RUN_CONTROL;e.side=operation;
  e.limitPx=word(0);e.priceTicks=word(1);e.orderRef=(int)(word(2)>>>32);e.accountId=(int)word(2);
  e.securityId=(int)(word(3)>>>32);e.qty=(int)word(3);e.eventTimeMillis=0;
 }
 public boolean matches(finos.traderx.ordermatcher.lmax.InputEvent e) {
  return e.limitPx==word(0) && e.priceTicks==word(1)
   && (((long)e.orderRef<<32)|Integer.toUnsignedLong(e.accountId))==word(2)
   && (((long)e.securityId<<32)|Integer.toUnsignedLong(e.qty))==word(3);
 }
}
