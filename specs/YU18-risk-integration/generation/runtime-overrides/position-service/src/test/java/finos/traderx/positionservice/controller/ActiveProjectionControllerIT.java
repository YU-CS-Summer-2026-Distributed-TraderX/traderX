package finos.traderx.positionservice.controller;

import finos.traderx.positionservice.repository.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real SQL pointer selection through the mapped controller; servlet transport is MockMvc. */
@Tag("integration") @Testcontainers
class ActiveProjectionControllerIT {
 @Container static final MariaDBContainer<?> DB=new MariaDBContainer<>("mariadb:11.4")
  .withCreateContainerCmdModifier(c->c.withName("traderx-o1-active-sql").getHostConfig().withMemory(536870912L));
 JdbcTemplate jdbc;MockMvc mvc;
 @BeforeEach void setUp() {
  jdbc=new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()));
  jdbc.execute("CREATE TABLE IF NOT EXISTS projection_runs(projection_scope VARCHAR(64) PRIMARY KEY,cluster_epoch VARCHAR(64),event_id_scheme VARCHAR(32),descriptor_hash CHAR(64),phase VARCHAR(32),checkpoint_seq BIGINT)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS projection_active(singleton_id INT PRIMARY KEY,projection_scope VARCHAR(64))");
  jdbc.update("DELETE FROM projection_active");jdbc.update("DELETE FROM projection_runs");
  jdbc.update("INSERT INTO projection_runs VALUES ('a','epoch_a','epoch-v1',?,'ACTIVE',10),('b','epoch_b','epoch-v1',?,'ACTIVE',20)","a".repeat(64),"b".repeat(64));
  mvc=MockMvcBuilders.standaloneSetup(new ScopedProjectionController(mock(PositionRepository.class),mock(TradeRepository.class),jdbc)).build();
 }
 @Test void readsActualPointerWithTwoActiveRowsAndImmediatelyReflectsSelection() throws Exception {
  jdbc.update("INSERT INTO projection_active VALUES (1,'b')");
  mvc.perform(get("/v2/projections/active")).andExpect(status().isOk()).andExpect(content().json("{\"projectionScope\":\"b\"}",true));
  jdbc.update("UPDATE projection_active SET projection_scope='a' WHERE singleton_id=1");
  mvc.perform(get("/v2/projections/active")).andExpect(status().isOk()).andExpect(content().json("{\"projectionScope\":\"a\"}",true));
  assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM projection_runs WHERE phase='ACTIVE'",Integer.class));
 }
 @Test void missingOrDanglingPointerRefusesWithoutInference() throws Exception {
  mvc.perform(get("/v2/projections/active")).andExpect(status().isServiceUnavailable());
  jdbc.update("INSERT INTO projection_active VALUES (1,'unknown')");
  mvc.perform(get("/v2/projections/active")).andExpect(status().isServiceUnavailable());
 }
 @Test void existingRegistryListKeepsItsShapeAndUnknownHistoryIs404() throws Exception {
  var response=mvc.perform(get("/v2/projections")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  var rows=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);assertTrue(rows.isArray());assertEquals(2,rows.size());
  var fields=new HashSet<String>();rows.get(0).fieldNames().forEachRemaining(fields::add);
  assertEquals(Set.of("projection_scope","cluster_epoch","event_id_scheme","descriptor_hash","phase","checkpoint_seq"),fields);
  mvc.perform(get("/v2/projections/unknown/accounts/1/positions")).andExpect(status().isNotFound());
  mvc.perform(get("/v2/projections/unknown/accounts/1/trades")).andExpect(status().isNotFound());
 }
}
