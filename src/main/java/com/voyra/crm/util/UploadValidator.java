package com.voyra.crm.util;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * The one place that decides which uploaded files this CRM accepts, called by every
 * {@code FileStorageService} implementation so no upload path can skip it. Checked by file
 * extension - the UI copy promises "PDF/JPG/PNG up to 10MB" - and then by the file's first bytes,
 * so an executable renamed to .pdf is not stored and later served back to staff.
 */
public final class UploadValidator {

    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("pdf", "jpg", "jpeg", "png", "webp", "doc", "docx", "xls", "xlsx", "csv");

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

    public static void assertAllowed(MultipartFile file) {
        String name = file.getOriginalFilename();
        int dot = name != null ? name.lastIndexOf('.') : -1;
        String extension = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(NOT_ALLOWED);
        }
        byte[] head;
        try (InputStream in = file.getInputStream()) {
            head = in.readNBytes(1024);
        } catch (IOException e) {
            throw new IllegalArgumentException("The file could not be read - try uploading it again");
        }
        if (!contentMatches(extension, head)) {
            throw new IllegalArgumentException("This file's content doesn't match its ." + extension
                    + " extension. Upload a real PDF, JPG, PNG, WEBP, Word, Excel or CSV file.");
        }
    }

    private static boolean contentMatches(String extension, byte[] head) {
        return switch (extension) {
            // A PDF header may legally sit anywhere in the first 1024 bytes.
            case "pdf" -> new String(head, StandardCharsets.ISO_8859_1).contains("%PDF-");
            case "png" -> startsWith(head, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
            case "jpg", "jpeg" -> startsWith(head, 0, 0xFF, 0xD8, 0xFF);
            case "webp" -> startsWith(head, 0, 'R', 'I', 'F', 'F') && startsWith(head, 8, 'W', 'E', 'B', 'P');
            case "docx", "xlsx" -> startsWith(head, 0, 'P', 'K', 0x03, 0x04);
            case "doc", "xls" -> startsWith(head, 0, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1);
            // CSV has no signature: accept text, refuse anything with a NUL byte (binary).
            case "csv" -> {
                for (byte b : head) {
                    if (b == 0) yield false;
                }
                yield true;
            }
            default -> false;
        };
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
