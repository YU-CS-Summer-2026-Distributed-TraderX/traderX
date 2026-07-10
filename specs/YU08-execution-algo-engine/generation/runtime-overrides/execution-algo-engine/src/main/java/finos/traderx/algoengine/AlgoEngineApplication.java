package finos.traderx.algoengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AlgoEngineApplication {

  public static void main(String[] args) {
    SpringApplication.run(AlgoEngineApplication.class, args);
  }
}
