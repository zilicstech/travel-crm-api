package com.voyra.crm.config;

import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.security.TurnstileVerifier;
import com.voyra.crm.service.PlatformSupplierCredentialService;
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
import static org.mockito.Mockito.when;

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

    private final ApplicationContextRunner supplierLogger = new ApplicationContextRunner()
            .withUserConfiguration(SupplierStartupLogger.class);

    @Test
    void supplierLoggerWarnsWhenFlightSearchIsMockAndReportsTheCredential() {
        PlatformSupplierCredentialService credentials = mock(PlatformSupplierCredentialService.class);
        when(credentials.hasActive(SupplierProvider.TRIPJACK)).thenReturn(true);
        supplierLogger.withBean(PlatformSupplierCredentialService.class, () -> credentials)
                // without the OS environment, so the answer does not depend on the machine running the test
                .withInitializer(c -> c.getEnvironment().getPropertySources()
                        .remove(org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
                .withPropertyValues("app.flight-supplier.provider=MOCK", "app.hotel-supplier.provider=tripjack")
                .run(ctx -> {
                    try (LogCapture log = new LogCapture(SupplierStartupLogger.class)) {
                        ctx.getBean(SupplierStartupLogger.class).logSupplierConfig();
                        assertThat(log.lines()).hasSize(3);
                        assertThat(log.lines().get(0)).isEqualTo("INFO Supplier config at startup: flightProvider=MOCK, hotelProvider=tripjack, FLIGHT_SUPPLIER env present=false");
                        assertThat(log.lines().get(1)).startsWith("WARN Flight search is using the MOCK provider");
                        assertThat(log.lines().get(2)).isEqualTo("INFO Platform Tripjack credential active=true");
                    }
                });
    }

    @Test
    void supplierLoggerSurvivesAnUnreadableCredential() {
        PlatformSupplierCredentialService credentials = mock(PlatformSupplierCredentialService.class);
        when(credentials.hasActive(SupplierProvider.TRIPJACK)).thenThrow(new IllegalStateException("no key"));
        supplierLogger.withBean(PlatformSupplierCredentialService.class, () -> credentials)
                .withPropertyValues("app.flight-supplier.provider=tripjack", "FLIGHT_SUPPLIER=tripjack")
                .run(ctx -> {
                    try (LogCapture log = new LogCapture(SupplierStartupLogger.class)) {
                        ctx.getBean(SupplierStartupLogger.class).logSupplierConfig();
                        assertThat(log.lines()).hasSize(2);
                        assertThat(log.lines().get(0)).endsWith("FLIGHT_SUPPLIER env present=true");
                        assertThat(log.lines().get(1)).isEqualTo("WARN Could not read the platform Tripjack credential at startup: no key");
                    }
                });
    }
}
