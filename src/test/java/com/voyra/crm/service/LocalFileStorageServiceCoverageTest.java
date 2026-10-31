package com.voyra.crm.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Local storage round trip: tenant-scoped keys, safe file names, no path escape, and IO failures surfaced. */
class LocalFileStorageServiceCoverageTest {

    private static final byte[] PDF = "%PDF-1.4\nfake test document".getBytes(StandardCharsets.US_ASCII);

    @Test
    void storesUnderTheTenantSchemaWithASafeNameThenRetrievesAndDeletes(@TempDir Path dir) throws IOException {
        LocalFileStorageService storage = new LocalFileStorageService(dir.resolve("nested/base").toString());
        MockMultipartFile file = new MockMultipartFile("file", "uploads/My Ticket (1).pdf", "application/pdf", PDF);

        String key = storage.store("ABC-123", "booking-documents", "D1", file);

        assertThat(key).startsWith("tenant_abc123/booking-documents/D1/").endsWith("-My_Ticket__1_.pdf");
        Path onDisk = dir.resolve("nested/base").resolve(key);
        assertThat(Files.readAllBytes(onDisk)).isEqualTo(PDF);
        Resource r = storage.retrieve(key);
        assertThat(r.getInputStream().readAllBytes()).isEqualTo(PDF);

        storage.delete(key);
        assertThat(Files.exists(onDisk)).isFalse();
        storage.delete(key); // already gone: no error
    }

    @Test
    void refusesAMissingOrEmptyFileAndADisallowedType(@TempDir Path dir) {
        LocalFileStorageService storage = new LocalFileStorageService(dir.toString());
        assertThatThrownBy(() -> storage.store("T1", "c", "o", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("File is required");
        assertThatThrownBy(() -> storage.store("T1", "c", "o", new MockMultipartFile("f", "a.pdf", "application/pdf", new byte[0])))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("File is required");
        assertThatThrownBy(() -> storage.store("T1", "c", "o", new MockMultipartFile("f", "a.exe", "x", new byte[]{1})))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not allowed");
    }

    @Test
    void aKeyThatEscapesTheBaseDirectoryIsRefused(@TempDir Path dir) {
        LocalFileStorageService storage = new LocalFileStorageService(dir.resolve("base").toString());
        assertThatThrownBy(() -> storage.retrieve("../outside.pdf"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid file key");
        assertThatThrownBy(() -> storage.delete("tenant_x/../../outside.pdf"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid file key");
    }

    @Test
    void aBaseDirectoryThatCannotBeCreatedFailsAtStartup(@TempDir Path dir) throws IOException {
        Path blocker = Files.writeString(dir.resolve("a-file"), "x");
        assertThatThrownBy(() -> new LocalFileStorageService(blocker.resolve("sub").toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Unable to create local storage base directory: ");
    }

    @Test
    void aWriteFailureIsReportedWithTheKey(@TempDir Path dir) throws IOException {
        LocalFileStorageService storage = new LocalFileStorageService(dir.toString());
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("a.pdf");
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(PDF));
        doThrow(new IOException("disk full")).when(file).transferTo(any(Path.class));

        assertThatThrownBy(() -> storage.store("T1", "c", "o", file))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("Failed to store file: tenant_t1/c/o/");
    }

    @Test
    void deletingANonEmptyDirectoryKeyIsReported(@TempDir Path dir) throws IOException {
        LocalFileStorageService storage = new LocalFileStorageService(dir.toString());
        Files.createDirectories(dir.resolve("tenant_t1/c"));
        Files.writeString(dir.resolve("tenant_t1/c/inner.pdf"), "x");

        assertThatThrownBy(() -> storage.delete("tenant_t1/c"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Failed to delete file: tenant_t1/c");
    }
}
