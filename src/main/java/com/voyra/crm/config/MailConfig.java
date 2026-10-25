package com.voyra.crm.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.util.StringUtils;

import java.util.Properties;

/** SMTP client from env (SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD, SMTP_STARTTLS, SMTP_SSL). Only when SMTP_HOST is set. */
@Configuration
@Conditional(SmtpConfigured.class)
public class MailConfig {

    @Bean
    public JavaMailSenderImpl javaMailSender(@Value("${app.mail.smtp-host}") String host,
                                             @Value("${app.mail.smtp-port:587}") int port,
                                             @Value("${app.mail.smtp-username:}") String username,
                                             @Value("${app.mail.smtp-password:}") String password,
                                             @Value("${app.mail.smtp-starttls:true}") boolean startTls,
                                             @Value("${app.mail.smtp-ssl:false}") boolean ssl) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host.trim());
        sender.setPort(port);
        sender.setDefaultEncoding("UTF-8");
        boolean auth = StringUtils.hasText(username);
        if (auth) {
            sender.setUsername(username);
            sender.setPassword(password);
        }
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", String.valueOf(auth));
        // SMTP_SSL: implicit TLS from the first byte (port 465); otherwise STARTTLS upgrades a plain connection (587)
        props.put("mail.smtp.ssl.enable", String.valueOf(ssl));
        props.put("mail.smtp.starttls.enable", String.valueOf(startTls && !ssl));
        props.put("mail.smtp.starttls.required", String.valueOf(startTls && !ssl));
        props.put("mail.smtp.ssl.checkserveridentity", "true");
        // A hung SMTP server must not stall the outbox job for long.
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        return sender;
    }
}
