package finos.traderx.ordermatcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// state YU01: no @EnableScheduling — the 009 matcher polling tick is replaced by the
// event-driven LMAX input disruptor (FR-09B02).
@SpringBootApplication
public class OrderMatcherApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderMatcherApplication.class, args);
    }
}
