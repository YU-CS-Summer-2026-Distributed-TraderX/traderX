package finos.traderx.accountservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * {@code @TestPropertySource} added alongside this state's outbox work: the Gradle {@code
 * sourceSets} fix that makes {@code src/main/test/java} actually run (see build.gradle) resurrects
 * this previously-dead-code smoke test, which would otherwise try the real MariaDB datasource
 * (unreachable in most dev/CI environments) instead of the H2 config {@code
 * test-application.properties} was clearly meant to provide.
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:test-application.properties")
class AccountServiceApplicationTests {

  @Test
  void contextLoads() {
    // smoke test
  }
}
