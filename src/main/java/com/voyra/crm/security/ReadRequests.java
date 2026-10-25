package com.voyra.crm.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.AntPathMatcher;

import java.util.List;
import java.util.Set;

/**
 * Which requests only read: GET/HEAD/OPTIONS plus the few POSTs that only compute or look up
 * (searches, previews, duplicate checks), listed exactly. A POST that records anything stays a write.
 * Shared by the SUSPENDED (423) and READ_ONLY (402) checks so the two never drift apart.
 */
public final class ReadRequests {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    public static final List<String> READ_STYLE_POSTS = List.of(
            "/api/flights/search", "/api/flights/search/paged",
            "/api/hotels/search", "/api/hotels/thumbnails", "/api/hotels/{id}/detail",
            "/api/clients/duplicate-check", "/api/accounts/invoices/preview-tax");
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private ReadRequests() {
    }

    /** path is the request URI without the context path. */
    public static boolean isRead(HttpServletRequest request, String path) {
        return READ_METHODS.contains(request.getMethod())
                || ("POST".equals(request.getMethod()) && READ_STYLE_POSTS.stream().anyMatch(p -> MATCHER.match(p, path)));
    }
}
