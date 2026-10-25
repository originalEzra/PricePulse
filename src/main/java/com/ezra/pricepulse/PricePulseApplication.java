package com.ezra.pricepulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Path;

@SpringBootApplication
public class PricePulseApplication {

    private static final String ROCKETMQ_LOG_ROOT = "rocketmq.log.root";

    public static void main(String[] args) {
        configureRocketMqLogRoot();
        SpringApplication.run(PricePulseApplication.class, args);
    }

    private static void configureRocketMqLogRoot() {
        if (System.getProperty(ROCKETMQ_LOG_ROOT) != null) {
            return;
        }

        String configuredRoot = System.getenv("ROCKETMQ_LOG_ROOT");
        String logRoot = configuredRoot == null || configuredRoot.isBlank()
                ? Path.of(System.getProperty("user.dir"), "logs", "rocketmq").toString()
                : configuredRoot;
        System.setProperty(ROCKETMQ_LOG_ROOT, logRoot);
    }
}
