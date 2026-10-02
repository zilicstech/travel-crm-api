package com.voyra.crm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** EnableScheduling backs the first @Scheduled job in this codebase - scheduler.LedgerIntegrityJob. */
@SpringBootApplication
@EnableScheduling
public class VoyraCrmApplication {

    public static void main(String[] args) {
        SpringApplication.run(VoyraCrmApplication.class, args);
    }
}
