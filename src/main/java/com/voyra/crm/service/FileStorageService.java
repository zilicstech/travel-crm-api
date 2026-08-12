package com.voyra.crm.service;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * Storage abstraction so callers (document upload endpoints) never know whether a file
 * lives on local disk or in GCS. Local implementation ships now for dev/testing; a GCS
 * implementation is a new class behind {@code app.storage.provider=gcs}, no caller changes.
 */
public interface FileStorageService {

    /** Stores the file and returns an opaque fileKey - the only thing ever persisted to the DB. */
    String store(String tenantId, String category, String ownerId, MultipartFile file);

    Resource retrieve(String fileKey);

    void delete(String fileKey);
}
