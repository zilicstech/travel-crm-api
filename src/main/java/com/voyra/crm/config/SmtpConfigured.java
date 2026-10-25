package com.voyra.crm.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/** True when app.mail.smtp-host (env SMTP_HOST) is set: email notifications replace the logging ones. */
public class SmtpConfigured implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return StringUtils.hasText(context.getEnvironment().getProperty("app.mail.smtp-host"));
    }

    /** No SMTP host: notices are only logged. */
    public static class Not implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !new SmtpConfigured().matches(context, metadata);
        }
    }
}
