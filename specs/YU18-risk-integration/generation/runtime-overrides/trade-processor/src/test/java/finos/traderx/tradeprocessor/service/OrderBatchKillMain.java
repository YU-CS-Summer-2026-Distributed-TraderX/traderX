package finos.traderx.tradeprocessor.service;

import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.repository.OrderRepository;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Test-only child JVM for OrderBatchProjectionIT's SIGKILL case: applies the adversarial stream in
 * batches through the real {@link OrderProjectionService#persistAll} and prints, after each commit,
 * {@code COMMITTED <batch> <order_updates> <orderbook rows>}. The parent kills it with SIGKILL while a
 * batch's transaction holds written rows. Args: url user password seed orders batchSize.
 *
 * <p>Its DataSource prints {@code UPSERTED} after each orderbook upsert statement and then holds the
 * batch's transaction open 300 ms (test-only, in this JVM): a batch's write is otherwise a couple of
 * milliseconds, too short for the parent to kill inside it reliably. The parent kills on that line,
 * so the batch has written rows it has not committed.
 */
public final class OrderBatchKillMain {
  @Configuration @EnableAutoConfiguration
  @EntityScan(basePackageClasses=Trade.class)
  @EnableJpaRepositories(basePackageClasses=TradeRepository.class)
  static class Config {
    @org.springframework.context.annotation.Bean
    static org.springframework.beans.factory.config.BeanPostProcessor slowUpserts() {
      return new org.springframework.beans.factory.config.BeanPostProcessor() {
        @Override public Object postProcessAfterInitialization(Object bean,String name) {
          return bean instanceof javax.sql.DataSource ds && !(bean instanceof SlowUpserts) ? new SlowUpserts(ds) : bean;
        }
      };
    }
  }

  static final class SlowUpserts extends org.springframework.jdbc.datasource.DelegatingDataSource {
    SlowUpserts(javax.sql.DataSource target) { super(target); }
    @Override public java.sql.Connection getConnection() throws java.sql.SQLException { return wrap(super.getConnection()); }
    static java.sql.Connection wrap(java.sql.Connection c) {
      return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(SlowUpserts.class.getClassLoader(),
          new Class<?>[]{java.sql.Connection.class},(proxy,m,args) -> {
            Object r=invoke(c,m,args);
            if (m.getName().equals("prepareStatement") && args!=null && args[0] instanceof String sql && sql.startsWith("INSERT INTO orderbook")) {
              java.sql.PreparedStatement ps=(java.sql.PreparedStatement) r;
              return java.lang.reflect.Proxy.newProxyInstance(SlowUpserts.class.getClassLoader(),
                  new Class<?>[]{java.sql.PreparedStatement.class},(p2,m2,a2) -> {
                    Object out=invoke(ps,m2,a2);
                    if (m2.getName().equals("executeUpdate")) {System.out.println("UPSERTED");System.out.flush();Thread.sleep(300);}
                    return out;
                  });
            }
            return r;
          });
    }
    private static Object invoke(Object target,java.lang.reflect.Method m,Object[] args) throws Throwable {
      try { return m.invoke(target,args); } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
    }
  }

  public static void main(String[] a) {
    // Command-line arguments, not builder defaults: the test classpath's own datasource settings
    // (an embedded H2) outrank defaults, and the child must write to the parent's MariaDB.
    var ctx=new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE).run(
        "--spring.datasource.url="+a[0],"--spring.datasource.username="+a[1],"--spring.datasource.password="+a[2],
        "--spring.datasource.driverClassName=org.mariadb.jdbc.Driver",
        "--spring.jpa.database-platform=org.hibernate.dialect.MariaDBDialect",
        "--spring.jpa.hibernate.ddl-auto=none","--spring.sql.init.mode=never",
        "--spring.jpa.properties.hibernate.jdbc.batch_size=1000");
    JdbcTemplate jdbc=ctx.getBean(JdbcTemplate.class);
    var service=new OrderProjectionService(ctx.getBean(OrderRepository.class),new RunRegistry(jdbc),
        new ProjectionWriteLock(jdbc),new org.springframework.transaction.support.TransactionTemplate(ctx.getBean(PlatformTransactionManager.class)));
    var batches=OrderBatchStream.batches(OrderBatchStream.adversarial(Long.parseLong(a[3]),Integer.parseInt(a[4])),Integer.parseInt(a[5]));
    for (int i=0;i<batches.size();i++) {
      service.persistAll(batches.get(i));
      Long updates=jdbc.queryForObject("SELECT order_updates FROM projection_runs WHERE projection_scope=?",Long.class,OrderBatchStream.SCOPE);
      Long rows=jdbc.queryForObject("SELECT COUNT(*) FROM orderbook",Long.class);
      System.out.println("COMMITTED "+i+" "+updates+" "+rows);
      System.out.flush();
    }
    System.out.println("DONE");
    System.exit(0);
  }
}
