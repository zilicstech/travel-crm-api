package com.voyra.crm.util;

import com.voyra.crm.service.booking.PlatformCancelRequestService;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/** How a cancellation proof file is served: inline, typed by its extension, never sniffed, never cached, sandboxed. */
public final class ProofFiles {

    private ProofFiles() {
    }

    public static ResponseEntity<Resource> inline(PlatformCancelRequestService.FileContent file) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(file.resource());
    }
}
