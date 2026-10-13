package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real transactions and SQL, independent of NATS; MariaDB acceptance is a separate tier. */
class SettlementServiceTest {
    DriverManagerDataSource ds;
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    ProjectionWriteLock lock;
    final Timestamp past = Timestamp.valueOf("2000-01-01 00:00:00");
    final Timestamp future = Timestamp.valueOf("2099-01-01 00:00:00");
    DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
    }
    @BeforeEach void setup() {
        ds = dataSource();
        jdbc = new JdbcTemplate(ds); manager = new DataSourceTransactionManager(ds);
        jdbc.execute("DROP TABLE IF EXISTS TRADES");
        jdbc.execute("DROP TABLE IF EXISTS PROJECTION_WRITE_LOCK");
        jdbc.execute("CREATE TABLE PROJECTION_WRITE_LOCK(LOCK_ID INT PRIMARY KEY)");
        jdbc.update("INSERT INTO PROJECTION_WRITE_LOCK VALUES(1)");
        jdbc.execute("CREATE TABLE TRADES(ID VARCHAR(100) PRIMARY KEY, STATE VARCHAR(30), SETTLEMENTDATE DATETIME, UPDATED DATETIME, PROJECTIONSCOPE VARCHAR(50), PRICE DECIMAL(18,6))");
        jdbc.execute("CREATE INDEX idx_trades_settlement_due ON TRADES(STATE,SETTLEMENTDATE,ID)");
        lock = new ProjectionWriteLock(jdbc);
    }
    SettlementService service(int size, int pages) {
        return new SettlementService(jdbc, lock, manager, new SimpleMeterRegistry(), size, pages, 30000);
    }
    void row(String id, String state, Timestamp date) {
        jdbc.update("INSERT INTO TRADES VALUES(?,?,?,?,?,?)", id,state,date,past,"scope-"+id,123.456789);
    }
    int count(String state) { return jdbc.queryForObject("SELECT COUNT(*) FROM TRADES WHERE STATE=?", Integer.class,state); }
    @Test void boundedBatchesResumeWithoutSkippingAndPreserveOtherFields() {
        for(int i=0;i<11;i++) row("due"+i,"Processing",past);
        row("future","Processing",future); row("null","Processing",null);
        row("rejected","Rejected",past); row("settled","Settled",past);
        var first=service(3,2); first.sweep(); assertEquals(6, first.sweptCount()); assertEquals(7,count("Settled"));
        // New service has no cursor; it resumes the committed shrinking due set.
        var restarted=service(3,2); restarted.sweep(); assertEquals(5,restarted.sweptCount());
        restarted.sweep(); assertEquals(5,restarted.sweptCount()); assertEquals(12,count("Settled"));
        assertEquals(2,count("Processing")); assertEquals(1,count("Rejected"));
        assertEquals(11,jdbc.queryForObject("SELECT COUNT(*) FROM TRADES WHERE ID LIKE 'due%' AND SETTLEMENTDATE=? AND PROJECTIONSCOPE=CONCAT('scope-',ID) AND PRICE=123.456789",Integer.class,past));
        assertEquals(past,jdbc.queryForObject("SELECT UPDATED FROM TRADES WHERE ID='settled'",Timestamp.class));
    }
    @Test void failedSecondBatchRollsBackWithoutCountingAndRestartCompletes() {
        for(int i=0;i<7;i++) row("due"+i,"Processing",past);
        AtomicInteger writes=new AtomicInteger();
        JdbcTemplate faulty=new JdbcTemplate(ds) {
            @Override public int update(String sql,Object... args) {
                int n=super.update(sql,args);
                if(sql.startsWith("UPDATE TRADES") && writes.incrementAndGet()==2) throw new IllegalStateException("injected after write before commit");
                return n;
            }
        };
        var failing=new SettlementService(faulty,lock,manager,new SimpleMeterRegistry(),3,5,30000);
        assertThrows(IllegalStateException.class,failing::sweep);
        assertEquals(3,count("Settled")); assertEquals(3,failing.sweptCount());
        var resumed=service(3,5);resumed.sweep();assertEquals(7,count("Settled"));assertEquals(4,resumed.sweptCount());
    }
    @Test void forcePreservesOverrideAndAlreadySettledSemantics() {
        row("rejected","Rejected",future);row("done","Settled",past);
        var s=service(2,1);
        assertEquals(SettlementService.ForceResult.NOT_FOUND,s.forceSettle("missing"));
        assertEquals(SettlementService.ForceResult.ALREADY_SETTLED,s.forceSettle("done"));
        assertEquals(past,jdbc.queryForObject("SELECT SETTLEMENTDATE FROM TRADES WHERE ID='done'",Timestamp.class));
        assertEquals(SettlementService.ForceResult.SETTLED,s.forceSettle("rejected"));
        assertEquals(SettlementService.ForceResult.ALREADY_SETTLED,s.forceSettle("rejected"));
        assertEquals(1,s.sweptCount());assertEquals(2,count("Settled"));
        assertTrue(jdbc.queryForObject("SELECT SETTLEMENTDATE FROM TRADES WHERE ID='rejected'",Timestamp.class).before(future));
    }
    @Test void forceRollbackDoesNotAdvanceCounter() {
        row("one","Processing",future);
        JdbcTemplate faulty=new JdbcTemplate(ds) {
            @Override public int update(String sql,Object... args) { super.update(sql,args);throw new IllegalStateException("rollback"); }
        };
        var s=new SettlementService(faulty,lock,manager,new SimpleMeterRegistry(),2,1,1000);
        assertThrows(IllegalStateException.class,()->s.forceSettle("one"));
        assertEquals(0,s.sweptCount());assertEquals(1,count("Processing"));
        assertEquals(future,jdbc.queryForObject("SELECT SETTLEMENTDATE FROM TRADES WHERE ID='one'",Timestamp.class));
    }
    @Test void concurrentSweepersAndForceCountEachTransitionOnce() throws Exception {
        for(int i=0;i<100;i++) row("due"+i,"Processing",past);
        var a=service(7,100);var b=service(9,100);
        try(var pool=Executors.newFixedThreadPool(3)) {
            Future<?> x=pool.submit(a::sweep), y=pool.submit(b::sweep), z=pool.submit(()->a.forceSettle("due50"));
            x.get(10,TimeUnit.SECONDS);y.get(10,TimeUnit.SECONDS);z.get(10,TimeUnit.SECONDS);
        }
        assertEquals(100,count("Settled"));assertEquals(100,a.sweptCount()+b.sweptCount());
    }
    @Test void projectionWriterSerializesAndDateIsRecheckedAfterLock() throws Exception {
        row("one","Processing",past);var s=service(2,1);
        CountDownLatch held=new CountDownLatch(1), release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Future<?> writer=pool.submit(()->new TransactionTemplate(manager).execute(status->{
                lock.acquire();jdbc.update("UPDATE TRADES SET SETTLEMENTDATE=? WHERE ID='one'",future);held.countDown();
                try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch(InterruptedException e){throw new RuntimeException(e);}return null;
            }));
            assertTrue(held.await(5,TimeUnit.SECONDS));Future<?> sweep=pool.submit(s::sweep);
            assertThrows(TimeoutException.class,()->sweep.get(100,TimeUnit.MILLISECONDS));
            release.countDown();writer.get(5,TimeUnit.SECONDS);sweep.get(5,TimeUnit.SECONDS);
        }
        assertEquals(1,count("Processing"));assertEquals(0,s.sweptCount());
    }
    @Test void missingGlobalLockFailsClosed() {
        row("one","Processing",past);jdbc.update("DELETE FROM PROJECTION_WRITE_LOCK");
        assertThrows(RuntimeException.class,()->service(2,1).sweep());assertEquals(1,count("Processing"));
    }
    @Test void forceParticipatesInOuterTransactionAndCountsOnlyItsCommit() {
        row("one","Processing",future);var s=service(2,1);var outer=new TransactionTemplate(manager);
        outer.execute(status->{
            assertEquals(SettlementService.ForceResult.SETTLED,s.forceSettle("one"));
            assertEquals(0,s.sweptCount());status.setRollbackOnly();return null;
        });
        assertEquals(1,count("Processing"));assertEquals(0,s.sweptCount());
        outer.execute(status->{
            assertEquals(SettlementService.ForceResult.SETTLED,s.forceSettle("one"));
            assertEquals(0,s.sweptCount());return null;
        });
        assertEquals(1,count("Settled"));assertEquals(1,s.sweptCount());
    }
    @Test void invalidBoundsFailAtConstruction() {
        assertThrows(IllegalArgumentException.class,()->service(0,1));
        assertThrows(IllegalArgumentException.class,()->service(2001,1));
        assertThrows(IllegalArgumentException.class,()->service(2,0));
    }
}
