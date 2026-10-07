package com.voyra.crm.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Credential source precedence for {@link GcsFileStorageService}: inline JSON, then file, then ADC. */
class GcsCredentialsResolutionTest {

    @TempDir
    Path tmp;

    /** A syntactically valid service-account key built from a throwaway in-memory RSA key. */
    private static String serviceAccountJson(String clientEmail) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(pair.getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
        // The real key file keeps the PEM's line breaks as JSON \n escapes inside one string.
        String escaped = pem.replace("\n", "\\n");
        return "{\"type\":\"service_account\",\"project_id\":\"test-project\",\"private_key_id\":\"abc123\","
                + "\"private_key\":\"" + escaped + "\",\"client_email\":\"" + clientEmail + "\","
                + "\"client_id\":\"1234567890\",\"token_uri\":\"https://oauth2.googleapis.com/token\"}";
    }

    @Test
    void inlineJsonIsParsedIntoServiceAccountCredentials() throws Exception {
        GoogleCredentials creds = GcsFileStorageService.resolveCredentials(
                serviceAccountJson("inline@test-project.iam.gserviceaccount.com"), "");

        ServiceAccountCredentials sa = assertInstanceOf(ServiceAccountCredentials.class, creds);
        assertEquals("inline@test-project.iam.gserviceaccount.com", sa.getClientEmail());
    }

    @Test
    void inlineJsonWinsOverAFilePath() throws Exception {
        Path file = tmp.resolve("key.json");
        Files.writeString(file, serviceAccountJson("fromfile@test-project.iam.gserviceaccount.com"));

        GoogleCredentials creds = GcsFileStorageService.resolveCredentials(
                serviceAccountJson("inline@test-project.iam.gserviceaccount.com"), file.toString());

        assertEquals("inline@test-project.iam.gserviceaccount.com",
                ((ServiceAccountCredentials) creds).getClientEmail());
    }

    @Test
    void fileIsUsedWhenThereIsNoInlineJson() throws Exception {
        Path file = tmp.resolve("key.json");
        Files.writeString(file, serviceAccountJson("fromfile@test-project.iam.gserviceaccount.com"));

        GoogleCredentials creds = GcsFileStorageService.resolveCredentials("", file.toString());

        assertEquals("fromfile@test-project.iam.gserviceaccount.com",
                ((ServiceAccountCredentials) creds).getClientEmail());
    }

    @Test
    void blankEverywhereFallsBackToDefaultCredentials() {
        assertNull(GcsFileStorageService.resolveCredentials("", ""));
        assertNull(GcsFileStorageService.resolveCredentials(null, null));
        assertNull(GcsFileStorageService.resolveCredentials("   ", "  "));
    }

    @Test
    void missingFileFailsWithTheOffendingPath() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> GcsFileStorageService.resolveCredentials("", tmp.resolve("nope.json").toString()));
        assertEquals(true, e.getMessage().contains("nope.json"));
    }

    @Test
    void malformedInlineJsonFailsWithoutEchoingTheKeyMaterial() throws Exception {
        String json = serviceAccountJson("x@test-project.iam.gserviceaccount.com");
        String broken = json.replace("service_account", "something_else");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> GcsFileStorageService.resolveCredentials(broken, ""));

        assertFalse(e.getMessage().contains("BEGIN PRIVATE KEY"));
        assertFalse(e.getMessage().contains("something_else"));
        assertNull(e.getCause(), "cause is dropped on purpose: a parser message can echo key material");
    }
}
