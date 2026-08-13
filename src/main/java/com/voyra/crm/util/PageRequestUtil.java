package com.voyra.crm.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** Normalises and bounds the optional page/size query parameters. */
public final class PageRequestUtil {

    private static final int DEFAULT_SIZE = 25;
    private static final int MAX_SIZE = 200;

    private PageRequestUtil() {
    }

    /** @return null when the caller did not request pagination */
    public static Pageable resolve(Integer page, Integer size) {
        if (page == null) {
            return null;
        }
        int safePage = Math.max(page, 0);
        int safeSize = size == null ? DEFAULT_SIZE : Math.min(Math.max(size, 1), MAX_SIZE);
        return PageRequest.of(safePage, safeSize);
    }
}
