package com.voyra.crm.service;

import com.voyra.crm.exception.StoredFileNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CRC-19: a missing file is a plain 404 that never names the storage key. */
class LocalFileStorageServiceTest {

    @Test
    void aMissingFileNeverLeaksItsStorageKey(@TempDir Path dir) {
        LocalFileStorageService storage = new LocalFileStorageService(dir.toString());

        assertThatThrownBy(() -> storage.retrieve("tenant_abc/member-documents/m1/x-crc-img.png"))
                .isInstanceOf(StoredFileNotFoundException.class)
                .hasMessage("File not found");
    }
}
