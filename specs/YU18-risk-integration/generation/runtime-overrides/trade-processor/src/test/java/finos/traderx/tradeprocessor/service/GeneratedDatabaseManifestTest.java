package finos.traderx.tradeprocessor.service;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import static org.junit.jupiter.api.Assertions.*;

/** Parse composed output, not a hand-extracted SQL fragment: block boundaries are contractual. */
class GeneratedDatabaseManifestTest {
 static final Path BASE=Path.of("../kubernetes-runtime/manifests/base");
 static Map<String,Object> manifest(String file) throws Exception {
  try(var input=Files.newInputStream(BASE.resolve(file))) {return new Yaml(new SafeConstructor(new LoaderOptions())).load(input);}
 }
 @SuppressWarnings("unchecked") static Map<String,String> scripts() throws Exception {
  var cm=manifest("database-init-configmap.yaml");assertEquals("ConfigMap",cm.get("kind"));
  assertEquals("database-init-sql",((Map<String,Object>)cm.get("metadata")).get("name"));
  return (Map<String,String>)cm.get("data");
 }
 @Test void bothGeneratedSqlKeysContainExactlyOneCompleteCanonicalMigration() throws Exception {
  var data=scripts();assertEquals(Set.of("001-initialSchema.sql","900-migrations.sql"),data.keySet());
  String canonical=Files.readString(Path.of("../postgres-database-replacement/mariadb-migrations/ri06.sql")).strip();
  for(String name:data.keySet()) {
   String sql=data.get(name).strip();assertTrue(sql.endsWith(canonical),name+" must end with the complete canonical migration");
   assertEquals(sql.indexOf(canonical),sql.lastIndexOf(canonical),name+" must include it exactly once");
  }
  assertTrue(data.get("001-initialSchema.sql").contains("DROP TABLE IF EXISTS trades"));
  assertFalse(data.get("900-migrations.sql").contains("DROP TABLE"),"retained startup must not use destructive initialization");
 }
 @SuppressWarnings("unchecked") @Test void generatedDeploymentSelectsFreshAndRetainedScriptsSeparately() throws Exception {
  Map<String,Object> spec=(Map<String,Object>)((Map<String,Object>)((Map<String,Object>)manifest("database-deployment.yaml").get("spec")).get("template")).get("spec");
  var volume=((List<Map<String,Object>>)spec.get("volumes")).stream().filter(x->"database-init-sql".equals(x.get("name"))).findFirst().orElseThrow();
  assertEquals("database-init-sql",((Map<String,Object>)volume.get("configMap")).get("name"));
  var init=((List<Map<String,Object>>)spec.get("initContainers")).stream().filter(x->"schema-migrate".equals(x.get("name"))).findFirst().orElseThrow();
  var mounts=(List<Map<String,Object>>)init.get("volumeMounts");
  assertTrue(mounts.stream().anyMatch(x->"database-init-sql".equals(x.get("name")) && "900-migrations.sql".equals(x.get("subPath")) && "/migrations/900-migrations.sql".equals(x.get("mountPath"))));
  String command=String.join("\n",(List<String>)init.get("command"));assertTrue(command.contains("< /migrations/900-migrations.sql"));assertFalse(command.contains("001-initialSchema.sql"));
  var db=((List<Map<String,Object>>)spec.get("containers")).stream().filter(x->"database".equals(x.get("name"))).findFirst().orElseThrow();
  var fresh=(List<Map<String,Object>>)db.get("volumeMounts");
  for(String key:List.of("001-initialSchema.sql","900-migrations.sql"))assertTrue(fresh.stream().anyMatch(x->"database-init-sql".equals(x.get("name")) && key.equals(x.get("subPath")) && ("/docker-entrypoint-initdb.d/"+key).equals(x.get("mountPath"))));
 }
}
