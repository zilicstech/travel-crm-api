package com.voyra.crm.config;

import com.voyra.crm.security.TurnstileVerifier;
import com.voyra.crm.support.LogCapture;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ConfigBeansTest {

    @Test
    void openApiDocumentsTheJwtBearerScheme() {
        OpenAPI api = new OpenApiConfig().voyraCrmOpenApi();
        assertThat(api.getInfo().getTitle()).isEqualTo("Voyra Global Travel CRM API");
        assertThat(api.getSecurity()).singleElement().satisfies(req -> assertThat(req).containsKey("bearerAuth"));
        SecurityScheme scheme = api.getComponents().getSecuritySchemes().get("bearerAuth");
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(scheme.getScheme()).isEqualTo("bearer");
        assertThat(scheme.getBearerFormat()).isEqualTo("JWT");
    }

    @Test
    void everyPlainDataSourceIsWrappedOnceAndOtherBeansPassThrough() {
        BeanPostProcessor post = new TenantDataSourceConfig().tenantDataSourcePostProcessor();
        DataSource raw = mock(DataSource.class);

        Object wrapped = post.postProcessAfterInitialization(raw, "dataSource");
        assertThat(wrapped).isInstanceOf(TenantSchemaDataSource.class);
        assertThat(post.postProcessAfterInitialization(wrapped, "dataSource")).isSameAs(wrapped);
        assertThat(post.postProcessAfterInitialization("not a data source", "other")).isEqualTo("not a data source");
    }

    @Test
    void tripjackClientUsesAFiveSecondConnectAndTheConfiguredReadTimeout() {
        RestClient.Builder builder = new TripjackConfig().tripjackRestClientBuilder(30);
        Object factory = ReflectionTestUtils.getField(builder, "requestFactory");
        assertThat(factory).isInstanceOf(SimpleClientHttpRequestFactory.class);
        assertThat(ReflectionTestUtils.getField(factory, "connectTimeout")).isEqualTo(5_000);
        assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(30_000);
    }

    @Test
    void turnstileWithASecretIsEnabledAndQuiet() {
        try (LogCapture log = new LogCapture(TurnstileConfig.class)) {
            TurnstileVerifier verifier = new TurnstileConfig().turnstileVerifier("s3cret", new MockEnvironment());
            assertThat(verifier.enabled()).isTrue();
            assertThat(log.lines()).isEmpty();
        }
    }

    @Test
    void turnstileWithoutASecretWarnsLocallyAndErrorsOnCloudRun() {
        try (LogCapture log = new LogCapture(TurnstileConfig.class)) {
            assertThat(new TurnstileConfig().turnstileVerifier("", new MockEnvironment()).enabled()).isFalse();
            MockEnvironment cloudRun = new MockEnvironment();
            cloudRun.setActiveProfiles("cloudrun");
            assertThat(new TurnstileConfig().turnstileVerifier(" ", cloudRun).enabled()).isFalse();
            assertThat(log.lines()).containsExactly(
                    "WARN TURNSTILE_SECRET is not set - CAPTCHA on public agency applications is skipped",
                    "ERROR TURNSTILE_SECRET is not set - public agency applications are NOT CAPTCHA-protected on this deployment");
        }
    }

    @Test
    void smtpWithoutAUsernameSendsUnauthenticatedAndWithoutStartTlsWhenDisabled() {
        JavaMailSenderImpl sender = new MailConfig().javaMailSender(" smtp.example.test ", 25, "", "ignored", false, false);
        assertThat(sender.getHost()).isEqualTo("smtp.example.test");
        assertThat(sender.getUsername()).isNull();
        assertThat(sender.getPassword()).isNull();
        assertThat(sender.getJavaMailProperties()).containsEntry("mail.smtp.auth", "false")
                .containsEntry("mail.smtp.ssl.enable", "false")
                .containsEntry("mail.smtp.starttls.enable", "false")
                .containsEntry("mail.smtp.starttls.required", "false");
    }
}
