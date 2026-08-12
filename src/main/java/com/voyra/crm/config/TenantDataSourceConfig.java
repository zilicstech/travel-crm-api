package com.voyra.crm.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import javax.sql.DataSource;

/** Wraps whatever DataSource auto-configuration produces with {@link TenantSchemaDataSource}. */
@Configuration
public class TenantDataSourceConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public BeanPostProcessor tenantDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && !(bean instanceof TenantSchemaDataSource)) {
                    return new TenantSchemaDataSource(ds);
                }
                return bean;
            }
        };
    }
}
