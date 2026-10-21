package com.voyra.crm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/** EnableScheduling backs the first @Scheduled job in this codebase - scheduler.LedgerIntegrityJob. */
@SpringBootApplication
@EnableScheduling
public class VoyraCrmApplication {

    public static void main(String[] args) {
        // Every agency trades in India: "today" (document dates, FY, month ends) is the Indian day, whatever zone the
        // host runs in. Storage is unaffected - hibernate.jdbc.time_zone=UTC still writes timestamps as UTC.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        SpringApplication.run(VoyraCrmApplication.class, args);
    }
}
