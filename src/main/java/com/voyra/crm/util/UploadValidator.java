package com.voyra.crm.util;

import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Set;

/**
 * The one place that decides which uploaded files this CRM accepts, called by every
 * {@code FileStorageService} implementation so no upload path can skip it. Checked by file
 * extension - the UI copy promises "PDF/JPG/PNG up to 10MB" - which stops an executable or script
 * being stored and later served back to staff.
 */
public final class UploadValidator {

    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("pdf", "jpg", "jpeg", "png", "webp", "doc", "docx", "xls", "xlsx", "csv");

    private UploadValidator() {
    }

    public static void assertAllowed(MultipartFile file) {
        String name = file.getOriginalFilename();
        int dot = name != null ? name.lastIndexOf('.') : -1;
        String extension = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                    "This file type is not allowed. Upload a PDF, JPG, PNG, WEBP, Word, Excel or CSV file.");
        }
    }
}
