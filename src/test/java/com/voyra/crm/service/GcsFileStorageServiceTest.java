package com.voyra.crm.service;

import com.google.auth.Credentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.voyra.crm.exception.StoredFileNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Object storage behaviour of GcsFileStorageService against a mocked GCS client (no network). */
class GcsFileStorageServiceTest {

    private static final byte[] PDF = "%PDF-1.4 test".getBytes(StandardCharsets.US_ASCII);

    private MockedStatic<StorageOptions> options;
    private StorageOptions.Builder builder;
    private Storage storage;

    @BeforeEach
    void mockGcs() {
        builder = mock(StorageOptions.Builder.class);
        StorageOptions built = mock(StorageOptions.class);
        storage = mock(Storage.class);
        when(builder.setProjectId("test-project")).thenReturn(builder);
        when(builder.build()).thenReturn(built);
        when(built.getService()).thenReturn(storage);
        options = mockStatic(StorageOptions.class);
        options.when(StorageOptions::newBuilder).thenReturn(builder);
    }

    @AfterEach
    void close() {
        options.close();
    }

    private static String serviceAccountJson() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(gen.generateKeyPair().getPrivate().getEncoded());
        String pem = ("-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n").replace("\n", "\\n");
        return "{\"type\":\"service_account\",\"project_id\":\"test-project\",\"private_key_id\":\"abc\",\"private_key\":\"" + pem
                + "\",\"client_email\":\"svc@test-project.iam.gserviceaccount.com\",\"client_id\":\"1\","
                + "\"token_uri\":\"https://oauth2.googleapis.com/token\"}";
    }

    @Test
    void inlineCredentialsAreHandedToTheClientAndThePrefixLeadsEveryObjectName() throws Exception {
        GcsFileStorageService gcs = new GcsFileStorageService("bucket-1", "prod", "test-project", "", serviceAccountJson());
        when(storage.createFrom(any(BlobInfo.class), any(InputStream.class))).thenReturn(null);

        String key = gcs.store("T-1", "invoices", "I1", new MockMultipartFile("f", "../my bill (1).pdf", "application/pdf", PDF));

        verify(builder).setCredentials(any(Credentials.class));
        assertThat(key).startsWith("tenant_t1/invoices/I1/").endsWith("-my_bill__1_.pdf");
        ArgumentCaptor<BlobInfo> info = ArgumentCaptor.forClass(BlobInfo.class);
        verify(storage).createFrom(info.capture(), any(InputStream.class));
        assertThat(info.getValue().getBucket()).isEqualTo("bucket-1");
        assertThat(info.getValue().getName()).isEqualTo("prod/" + key);
        assertThat(info.getValue().getContentType()).isEqualTo("application/pdf");

        gcs.delete(key);
        verify(storage).delete(BlobId.of("bucket-1", "prod/" + key));
    }

    @Test
    void withoutCredentialsOrPrefixTheDefaultsApply() {
        GcsFileStorageService gcs = new GcsFileStorageService("bucket-1", null, "test-project", "", "");

        gcs.delete("tenant_t1/a/b/c.pdf");

        verify(builder, never()).setCredentials(any());
        verify(storage).delete(BlobId.of("bucket-1", "tenant_t1/a/b/c.pdf"));
    }

    @Test
    void anEmptyOrMissingFileIsRefused() {
        GcsFileStorageService gcs = new GcsFileStorageService("bucket-1", "", "test-project", "", "");

        assertThatThrownBy(() -> gcs.store("T1", "c", "o", null)).hasMessage("File is required");
        assertThatThrownBy(() -> gcs.store("T1", "c", "o", new MockMultipartFile("f", "a.pdf", "application/pdf", new byte[0])))
                .hasMessage("File is required");
        verifyNoInteractions(storage);
    }

    @Test
    void aStorageFailureIsReportedWithTheKey() throws Exception {
        GcsFileStorageService gcs = new GcsFileStorageService("bucket-1", "", "test-project", "", "");
        when(storage.createFrom(any(BlobInfo.class), any(InputStream.class))).thenThrow(new IOException("down"));

        assertThatThrownBy(() -> gcs.store("T1", "c", "o", new MockMultipartFile("f", "a.pdf", "application/pdf", PDF)))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("Failed to store file: tenant_t1/c/o/")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void aStoredFileIsReadBackWithItsNameAndAMissingOneIsNotFound() throws Exception {
        GcsFileStorageService gcs = new GcsFileStorageService("bucket-1", " ", "test-project", "", "");
        Blob blob = mock(Blob.class);
        when(blob.exists()).thenReturn(true);
        when(blob.getContent()).thenReturn(PDF);
        Blob gone = mock(Blob.class);
        when(gone.exists()).thenReturn(false);
        when(storage.get(BlobId.of("bucket-1", "tenant_t1/c/o/x-a.pdf"))).thenReturn(blob);
        when(storage.get(BlobId.of("bucket-1", "tenant_t1/c/o/gone.pdf"))).thenReturn(gone);
        when(storage.get(BlobId.of("bucket-1", "tenant_t1/c/o/none.pdf"))).thenReturn(null);

        Resource r = gcs.retrieve("tenant_t1/c/o/x-a.pdf");

        assertThat(r.getFilename()).isEqualTo("x-a.pdf");
        assertThat(r.getContentAsByteArray()).isEqualTo(PDF);
        assertThatThrownBy(() -> gcs.retrieve("tenant_t1/c/o/gone.pdf")).isInstanceOf(StoredFileNotFoundException.class);
        assertThatThrownBy(() -> gcs.retrieve("tenant_t1/c/o/none.pdf")).isInstanceOf(StoredFileNotFoundException.class);
    }
}
