package com.voyra.crm.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/** The connection pool is sized per deployment from DB_POOL_SIZE (default 10), in the base config and the Cloud Run profile. */
class DatabasePoolConfigTest {

    @Test
    void poolSizeComesFromTheEnvironmentInEveryProfile() throws IOException {
        for (String file : new String[]{"application.properties", "application-cloudrun.properties"}) {
            assertThat(PropertiesLoaderUtils.loadProperties(new ClassPathResource(file))
                    .getProperty("spring.datasource.hikari.maximum-pool-size")).as(file).isEqualTo("${DB_POOL_SIZE:10}");
        }
    }
}
