package com.voyra.crm.util;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The one place that decides which uploaded files this CRM accepts, called by every
 * {@code FileStorageService} implementation so no upload path can skip it. Checked by file
 * extension - the UI copy promises "PDF/JPG/PNG up to 10MB" - and then by the file's first bytes,
 * so an executable renamed to .pdf is not stored and later served back to staff.
 */
public final class UploadValidator {

    /** Every accepted extension, with the check its first bytes must pass. */
    private static final Map<String, Predicate<byte[]>> CONTENT_CHECKS = Map.of(
            // A PDF header may legally sit anywhere in the first 1024 bytes.
            "pdf", head -> new String(head, StandardCharsets.ISO_8859_1).contains("%PDF-"),
            "png", head -> startsWith(head, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A),
            "jpg", UploadValidator::jpeg, "jpeg", UploadValidator::jpeg,
            "webp", head -> startsWith(head, 0, 'R', 'I', 'F', 'F') && startsWith(head, 8, 'W', 'E', 'B', 'P'),
            "docx", UploadValidator::zip, "xlsx", UploadValidator::zip,
            "doc", UploadValidator::ole, "xls", UploadValidator::ole,
            // CSV has no signature: accept text, refuse anything with a NUL byte (binary).
            "csv", UploadValidator::csvText);

    public static final long MAX_LOGO_BYTES = 1024L * 1024;
    private static final Map<String, String> LOGO_TYPES = Map.of(
            "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg", "webp", "image/webp");
    public static final String LOGO_MESSAGE = "Upload the logo as a PNG, JPG or WEBP image of at most 1 MB";

    private static final String NOT_ALLOWED =
            "This file type is not allowed. Upload a PDF, JPG, PNG, WEBP, Word, Excel or CSV file.";

    private UploadValidator() {
    }

    /** As {@link #assertAllowed(MultipartFile)}, with a tighter size cap than the server-wide multipart limit. */
    public static void assertAllowed(MultipartFile file, long maxBytes) {
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException("File is larger than " + maxBytes / (1024 * 1024) + " MB");
        }
        assertAllowed(file);
    }

    /** An agency logo: PNG, JPEG or WebP by extension and by its first bytes, at most 1 MB. */
    public static void assertLogo(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_LOGO_BYTES || logoContentType(file.getOriginalFilename()) == null) {
            throw new IllegalArgumentException(LOGO_MESSAGE);
        }
        try {
            assertAllowed(file);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(LOGO_MESSAGE);
        }
    }

    /** The image type of a logo file name or storage key, by extension (the upload checked the bytes match); null if not a logo type. */
    public static String logoContentType(String name) {
        int dot = name != null ? name.lastIndexOf('.') : -1;
        return dot >= 0 ? LOGO_TYPES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT)) : null;
    }

    public static void assertAllowed(MultipartFile file) {
        String name = file.getOriginalFilename();
        int dot = name != null ? name.lastIndexOf('.') : -1;
        String extension = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!CONTENT_CHECKS.containsKey(extension)) {
            throw new IllegalArgumentException(NOT_ALLOWED);
        }
        byte[] head;
        try (InputStream in = file.getInputStream()) {
            head = in.readNBytes(1024);
        } catch (IOException e) {
            throw new IllegalArgumentException("The file could not be read - try uploading it again");
        }
        if (!CONTENT_CHECKS.get(extension).test(head)) {
            throw new IllegalArgumentException("This file's content doesn't match its ." + extension
                    + " extension. Upload a real PDF, JPG, PNG, WEBP, Word, Excel or CSV file.");
        }
    }

    private static boolean jpeg(byte[] head) {
        return startsWith(head, 0, 0xFF, 0xD8, 0xFF);
    }

    private static boolean zip(byte[] head) {
        return startsWith(head, 0, 'P', 'K', 0x03, 0x04);
    }

    private static boolean ole(byte[] head) {
        return startsWith(head, 0, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1);
    }

    private static boolean csvText(byte[] head) {
        for (byte b : head) {
            if (b == 0) return false;
        }
        return true;
    }

    private static boolean startsWith(byte[] head, int offset, int... signature) {
        if (head.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((head[offset + i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
