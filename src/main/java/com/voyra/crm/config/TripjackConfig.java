package com.voyra.crm.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * A dedicated, timeout-bounded {@link RestClient.Builder} for Tripjack calls only, separate
 * from the general-purpose prototype bean Spring's {@code RestClientAutoConfiguration}
 * provides. Kept out of {@code TripjackFlightSearchProvider}/{@code TripjackHotelSearchProvider}
 * themselves so their tests can bind {@code MockRestServiceServer} to a plain builder without
 * this timeout-configured request factory overwriting the mock's own - the two providers
 * never call {@code .requestFactory(...)} at all, only {@code .baseUrl(...)}/
 * {@code .defaultHeader(...)} on whatever builder they were given.
 */
@Configuration
public class TripjackConfig {

    /** Live 2026-10-30: a PROD review took over 15 s, so the read timeout is a setting (default 60 s). */
    @Bean
    public RestClient.Builder tripjackRestClientBuilder(@Value("${app.tripjack.read-timeout-seconds:60}") int readTimeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(readTimeoutSeconds * 1000);
        return RestClient.builder().requestFactory(factory);
    }
}
