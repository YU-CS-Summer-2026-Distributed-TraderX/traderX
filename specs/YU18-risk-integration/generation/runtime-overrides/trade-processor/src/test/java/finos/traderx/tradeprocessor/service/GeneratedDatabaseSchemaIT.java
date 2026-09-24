package finos.traderx.tradeprocessor.service;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ByteArrayResource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/** Execute the SQL parsed from the GENERATED ConfigMap, not standalone ri06.sql. */
@Tag("integration") @Testcontainers
class GeneratedDatabaseSchemaIT {
 @Container static final MariaDBContainer<?> DB=new MariaDBContainer<>("mariadb:11.4")
  .withCommand("--lower_case_table_names=1").withCreateContainerCmdModifier(c->c.withName("traderx-ri06-configmap-sql").getHostConfig().withMemory(536870912L).withNanoCPUs(500000000L));
 static final String TRADES="SELECT * FROM trades ORDER BY id";
 static final String POSITIONS="SELECT * FROM positions ORDER BY accountid,security";
 static final String ORDERS="SELECT * FROM orderbook ORDER BY orderid";
 @Test void freshVolumeRuns001Then900AndPreservesSeededEconomics() throws Exception {exercise(false);}
 @Test void populatedPreRi06VolumeRunsOnly900AndRepeatedMigrationPreservesHistory() throws Exception {exercise(true);}
 void exercise(boolean retained) throws Exception {
  var scripts=GeneratedDatabaseManifestTest.scripts();String initial=scripts.get("001-initialSchema.sql"),migration=scripts.get("900-migrations.sql");
  String database=retained?"c1_retained":"c1_fresh";
  try(var connection=DriverManager.getConnection(DB.getJdbcUrl(),"root",DB.getPassword())) {
   try(var statement=connection.createStatement()){statement.execute("CREATE DATABASE "+database);}
   connection.setCatalog(database);var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
   try {
    String setup=retained?initial.substring(0,initial.indexOf("-- RI-06: serialize deduplication")):initial;
    execute(connection,setup);
    assertEquals(retained?0:1,jdbc.queryForObject("SELECT count(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='positions' AND COLUMN_NAME='projectionscope'",Integer.class));
    int account=jdbc.queryForObject("SELECT MIN(id) FROM accounts",Integer.class);
    jdbc.update("INSERT INTO orderbook(orderid,accountid,security,side,quantity,remainingquantity,limitprice,status,createdat,updatedat,stopprice) VALUES ('historical-1',?,'IBM','Buy',17,17,101.25,'PENDING_TRIGGER','2026-09-23 10:00:00','2026-09-23 10:00:01',102.5)",account);
    if(retained) {jdbc.update("DELETE FROM trades ORDER BY id LIMIT 1");jdbc.update("DELETE FROM positions ORDER BY accountid,security LIMIT 1");}
    var trades=jdbc.queryForList(TRADES);var positions=jdbc.queryForList(POSITIONS);var orders=jdbc.queryForList(ORDERS);
    assertFalse(trades.isEmpty());assertFalse(positions.isEmpty());assertEquals(1,orders.size());
    for(int attempt=0;attempt<2;attempt++) {
     execute(connection,migration);
     preserved(trades,jdbc.queryForList(TRADES));preserved(positions,jdbc.queryForList(POSITIONS));preserved(orders,jdbc.queryForList(ORDERS));
     assertEquals(2,jdbc.queryForObject("SELECT version FROM projection_schema WHERE singleton_id=1",Integer.class));
     assertEquals("legacy-unknown",jdbc.queryForObject("SELECT projection_scope FROM projection_active WHERE singleton_id=1",String.class));
     assertEquals(List.of("projectionscope","accountid","security"),jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='positions' AND INDEX_NAME='PRIMARY' ORDER BY SEQ_IN_INDEX",String.class));
     assertEquals(List.of("cluster_epoch"),jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='projection_runs' AND INDEX_NAME='uq_run_order_namespace' ORDER BY SEQ_IN_INDEX",String.class));
     assertEquals(50,jdbc.queryForObject("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orderbook' AND COLUMN_NAME='orderid'",Integer.class));
     assertEquals(positions.size(),jdbc.queryForObject("SELECT count(*) FROM positions WHERE projectionscope='legacy-unknown'",Integer.class));
     assertEquals(trades.size(),jdbc.queryForObject("SELECT count(*) FROM trades WHERE projectionscope='legacy-unknown'",Integer.class));
    }
    System.out.println("C1_GENERATED_SQL_WITNESS path="+(retained?"retained-900-only":"fresh-001-then-900")+" trades="+trades.size()+" positions="+positions.size()+" orders="+orders.size()+" repeats=2 historyUnchanged=true schemaVersion=2");
   } finally {try(var statement=connection.createStatement()){statement.execute("DROP DATABASE "+database);}}
  }
 }
 static void preserved(List<Map<String,Object>> before,List<Map<String,Object>> after) {
  assertFalse(before.isEmpty());assertEquals(before.size(),after.size());
  // Every pre-existing column must remain identical; new RI06 columns are checked separately.
  var columns=before.get(0).keySet();for(var row:after)row.keySet().retainAll(columns);
  assertEquals(before,after);
 }
 static void execute(Connection connection,String sql) {ScriptUtils.executeSqlScript(connection,new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));}
}
