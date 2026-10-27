package com.voyra.crm.service.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PaxCodecTest {

    private final PaxCodec codec = new PaxCodec(new ObjectMapper(), "dGVzdC1vbmx5LWFlcy1rZXktMzItYnl0ZXMtbG9uZyE=");

    @Test
    void redactDropsApiKeysAndMasksPassportNumbersToTheLastThree() {
        Map<String, Object> out = codec.redact(Map.of("apikey", "secret", "headers", Map.of("apiKey", "secret2"),
                "travellers", List.of(Map.of("passportNo", "N1234567", "passportExpiry", "2031-01-31", "firstName", "Rahul"))));

        assertThat(out.toString()).doesNotContain("secret").doesNotContain("N1234567")
                .contains("*****567").contains("2031-01-31").contains("Rahul");
    }

    @Test
    void encryptRoundTrips() {
        String enc = codec.encrypt(Map.of("firstName", "Rahul"));

        assertThat(enc).doesNotContain("Rahul");
        assertThat(codec.decrypt(enc, Map.class)).containsEntry("firstName", "Rahul");
    }
}
