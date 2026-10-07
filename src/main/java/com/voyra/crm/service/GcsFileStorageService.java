package com.voyra.crm.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.voyra.crm.util.IdGenerator;
import com.voyra.crm.util.TenantSchemaUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

/**
 * Production implementation storing files as objects in a GCS bucket, behind
 * {@code app.storage.provider=gcs}. fileKey shape matches {@link LocalFileStorageService} exactly
 * - callers never know which provider is active.
 */
@Service
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "gcs")
@Slf4j
public class GcsFileStorageService implements FileStorageService {

    private final Storage storage;
    private final String bucket;
    private final String objectPrefix;

    public GcsFileStorageService(
            @Value("${app.storage.gcs.bucket}") String bucket,
            @Value("${app.storage.gcs.object-prefix:}") String objectPrefix,
            @Value("${app.storage.gcs.project-id}") String projectId,
            @Value("${app.storage.gcs.credentials-file:}") String credentialsFile,
            @Value("${app.storage.gcs.credentials-json:}") String credentialsJson) {
        this.bucket = bucket;
        this.objectPrefix = objectPrefix == null ? "" : objectPrefix;

        StorageOptions.Builder options = StorageOptions.newBuilder().setProjectId(projectId);
        GoogleCredentials credentials = resolveCredentials(credentialsJson, credentialsFile);
        if (credentials != null) {
            options.setCredentials(credentials);
        }
        this.storage = options.build().getService();
        log.info("GCS file storage active: bucket={} prefix={}", bucket, this.objectPrefix);
    }

    /**
     * Inline service-account JSON wins over a key file, and a blank for both falls back to Google's
     * default credentials (local gcloud login, GCE/Cloud Run identity). The inline form exists for
     * hosts with no persistent filesystem or file mounts (e.g. DigitalOcean App Platform), where a
     * key file path cannot exist. Neither the JSON nor any part of the key is ever logged.
     */
    static GoogleCredentials resolveCredentials(String credentialsJson, String credentialsFile) {
        if (credentialsJson != null && !credentialsJson.isBlank()) {
            try (InputStream in = new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8))) {
                return GoogleCredentials.fromStream(in);
            } catch (IOException | RuntimeException e) {
                // Deliberately not chaining e: a parser message can echo part of the key material.
                throw new IllegalStateException(
                        "Unable to parse GCS credentials from GCP_CREDENTIALS_JSON - it must be the complete "
                                + "service-account key JSON on a single line");
            }
        }
        if (credentialsFile != null && !credentialsFile.isBlank()) {
            try (InputStream in = new FileInputStream(credentialsFile)) {
                return GoogleCredentials.fromStream(in);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to load GCS credentials from " + credentialsFile, e);
            }
        }
        return null;
    }

    @Override
    public String store(String tenantId, String category, String ownerId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }
        String sanitizedName = sanitizeFilename(file.getOriginalFilename());
        String fileKey = "%s/%s/%s/%s-%s".formatted(
                TenantSchemaUtil.toSchemaName(tenantId), category, ownerId, IdGenerator.generateId(), sanitizedName);

        BlobId blobId = BlobId.of(bucket, objectName(fileKey));
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType(file.getContentType()).build();
        try {
            storage.createFrom(blobInfo, file.getInputStream());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store file: " + fileKey, e);
        }
        log.info("Stored file in GCS: {}", fileKey);
        return fileKey;
    }

    @Override
    public Resource retrieve(String fileKey) {
        Blob blob = storage.get(BlobId.of(bucket, objectName(fileKey)));
        if (blob == null || !blob.exists()) {
            throw new IllegalArgumentException("File not found: " + fileKey);
        }
        return new ByteArrayResource(blob.getContent()) {
            @Override
            public String getFilename() {
                return Paths.get(fileKey).getFileName().toString();
            }
        };
    }

    @Override
    public void delete(String fileKey) {
        storage.delete(BlobId.of(bucket, objectName(fileKey)));
    }

    private String objectName(String fileKey) {
        return objectPrefix.isBlank() ? fileKey : objectPrefix + "/" + fileKey;
    }

    private String sanitizeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "file";
        }
        String nameOnly = Paths.get(originalFilename).getFileName().toString();
        return nameOnly.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
