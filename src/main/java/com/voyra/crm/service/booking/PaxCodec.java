package com.voyra.crm.service.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.util.PasswordEncryptionUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Passenger PII at rest (AES via PasswordEncryptionUtil) and the redaction every booking event payload goes through. */
@Component
public class PaxCodec {

    private final ObjectMapper objectMapper;
    private final String key;

    public PaxCodec(ObjectMapper objectMapper, @Value("${app.security.password-encryption-key}") String key) {
        this.objectMapper = objectMapper;
        this.key = key;
    }

    public String encrypt(Object value) {
        try {
            return PasswordEncryptionUtil.encrypt(objectMapper.writeValueAsString(value), key);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise passengers", e);
        }
    }

    public <T> T decrypt(String encrypted, Class<T> type) {
        try {
            return objectMapper.readValue(PasswordEncryptionUtil.decrypt(encrypted, key), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read passengers", e);
        }
    }

    /** Hex SHA-256 of {@code value}'s JSON; pass ordered structures (lists, beans), never a Map.of. */
    public String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(value)));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not hash the confirm payload", e);
        }
    }

    /** Converts {@code value} to a JSON-shaped map with API keys dropped and passport numbers masked to the last 3. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> redact(Object value) {
        return (Map<String, Object>) redactNode(objectMapper.convertValue(value, Object.class), ""); // "" names the top level
    }

    private static Object redactNode(Object node, String key) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String name = String.valueOf(k);
                if (!name.toLowerCase(Locale.ROOT).replace("_", "").equals("apikey")) {
                    out.put(name, redactNode(v, name));
                }
            });
            return out;
        }
        if (node instanceof List<?> list) {
            return list.stream().map(v -> redactNode(v, key)).toList();
        }
        if (node instanceof String s && isPassportNumber(key)) {
            return s.length() <= 3 ? "***" : "*".repeat(s.length() - 3) + s.substring(s.length() - 3);
        }
        return node;
    }

    private static boolean isPassportNumber(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("passport") && !k.contains("expiry");
    }
}
