package com.voyra.crm.service;

import com.voyra.crm.util.IdGenerator;
import com.voyra.crm.util.TenantSchemaUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Dev/testing implementation storing files under a base directory on the local filesystem.
 * fileKey shape: tenant_&lt;id&gt;/&lt;category&gt;/&lt;ownerId&gt;/&lt;randomId&gt;-&lt;sanitized-original-name&gt;
 * - callers only ever handle this key, never a filesystem path.
 */
@Service
@Slf4j
public class LocalFileStorageService implements FileStorageService {

    private final Path baseDir;

    public LocalFileStorageService(@Value("${app.storage.local.base-dir}") String baseDirProperty) {
        this.baseDir = Paths.get(baseDirProperty).toAbsolutePath().normalize();
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create local storage base directory: " + baseDir, e);
        }
    }

    @Override
    public String store(String tenantId, String category, String ownerId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }
        String sanitizedName = sanitizeFilename(file.getOriginalFilename());
        String fileKey = "%s/%s/%s/%s-%s".formatted(
                TenantSchemaUtil.toSchemaName(tenantId), category, ownerId, IdGenerator.generateId(), sanitizedName);

        Path target = resolveWithinBaseDir(fileKey);
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store file: " + fileKey, e);
        }
        log.info("Stored file: {}", fileKey);
        return fileKey;
    }

    @Override
    public Resource retrieve(String fileKey) {
        Path target = resolveWithinBaseDir(fileKey);
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("File not found: " + fileKey);
        }
        try {
            return new UrlResource(target.toUri());
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Failed to resolve file: " + fileKey, e);
        }
    }

    @Override
    public void delete(String fileKey) {
        Path target = resolveWithinBaseDir(fileKey);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete file: " + fileKey, e);
        }
    }

    /** Resolves fileKey under baseDir and rejects any attempt to escape it (path traversal). */
    private Path resolveWithinBaseDir(String fileKey) {
        Path resolved = baseDir.resolve(fileKey).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("Invalid file key");
        }
        return resolved;
    }

    private String sanitizeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "file";
        }
        String nameOnly = Paths.get(originalFilename).getFileName().toString();
        return nameOnly.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
