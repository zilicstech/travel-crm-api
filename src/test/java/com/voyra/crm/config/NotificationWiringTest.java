package com.voyra.crm.config;

import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.NotificationOutboxRepository;
import com.voyra.crm.repository.PlatformAdminRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.scheduler.NotificationOutboxJob;
import com.voyra.crm.service.EmailNotificationService;
import com.voyra.crm.service.LoggingNotificationService;
import com.voyra.crm.service.NotificationOutboxService;
import com.voyra.crm.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Exactly one NotificationService: logging without SMTP_HOST, email (plus the SMTP client and the outbox job) with it. */
class NotificationWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(TenantRepository.class, () -> mock(TenantRepository.class))
            .withBean(PlatformAdminRepository.class, () -> mock(PlatformAdminRepository.class))
            .withBean(AgentRepository.class, () -> mock(AgentRepository.class))
            .withBean(NotificationOutboxRepository.class, () -> mock(NotificationOutboxRepository.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withUserConfiguration(MailConfig.class, LoggingNotificationService.class, EmailNotificationService.class,
                    NotificationOutboxService.class, NotificationOutboxJob.class);

    @Test
    void withoutSmtpHostNotificationsAreOnlyLogged() {
        runner.withPropertyValues("app.mail.smtp-host=").run(ctx -> {
            assertThat(ctx).hasSingleBean(NotificationService.class);
            assertThat(ctx.getBean(NotificationService.class)).isInstanceOf(LoggingNotificationService.class);
            assertThat(ctx).doesNotHaveBean(JavaMailSender.class).doesNotHaveBean(NotificationOutboxJob.class);
        });
    }

    @Test
    void withSmtpHostNotificationsAreEmailed() {
        runner.withPropertyValues("app.mail.smtp-host=smtp.voyra.test", "app.mail.smtp-port=2525",
                "app.mail.smtp-username=mailer", "app.mail.smtp-password=secret", "app.mail.from=Voyra <no-reply@voyra.test>").run(ctx -> {
            assertThat(ctx).hasSingleBean(NotificationService.class);
            assertThat(ctx.getBean(NotificationService.class)).isInstanceOf(EmailNotificationService.class);
            assertThat(ctx).hasSingleBean(NotificationOutboxJob.class);
            JavaMailSenderImpl sender = ctx.getBean(JavaMailSenderImpl.class);
            assertThat(sender.getHost()).isEqualTo("smtp.voyra.test");
            assertThat(sender.getPort()).isEqualTo(2525);
            assertThat(sender.getJavaMailProperties()).containsEntry("mail.smtp.auth", "true")
                    .containsEntry("mail.smtp.starttls.enable", "true").containsEntry("mail.smtp.ssl.checkserveridentity", "true");
        });
    }

    /** SMTP_SSL=true: implicit TLS (port 465), no STARTTLS, and the server certificate must match the host. */
    @Test
    void smtpSslUsesImplicitTlsAndChecksTheServerIdentity() {
        runner.withPropertyValues("app.mail.smtp-host=smtp.voyra.test", "app.mail.smtp-port=465", "app.mail.smtp-ssl=true",
                "app.mail.from=no-reply@voyra.test").run(ctx -> {
            JavaMailSenderImpl sender = ctx.getBean(JavaMailSenderImpl.class);
            assertThat(sender.getPort()).isEqualTo(465);
            assertThat(sender.getJavaMailProperties()).containsEntry("mail.smtp.ssl.enable", "true")
                    .containsEntry("mail.smtp.starttls.enable", "false").containsEntry("mail.smtp.ssl.checkserveridentity", "true");
        });
    }

    @Test
    void smtpWithoutMailFromRefusesToStart() {
        runner.withPropertyValues("app.mail.smtp-host=smtp.voyra.test").run(ctx -> assertThat(ctx).hasFailed());
    }
}
