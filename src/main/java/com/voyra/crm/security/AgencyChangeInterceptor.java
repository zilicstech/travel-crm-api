package com.voyra.crm.security;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.service.AgencyEventHub;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * After a write in an agency went through (its transaction committed before the response), tells the agency's open
 * screens to re-read their counts. Reads, failures and requests with no agency (platform, sign-in) say nothing.
 */
@Component
@RequiredArgsConstructor
public class AgencyChangeInterceptor implements HandlerInterceptor {

    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

    /** A provider, so the controller slice tests (which carry no services) still load this interceptor. */
    private final ObjectProvider<AgencyEventHub> hub;

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                @NonNull Object handler, @Nullable Exception ex) {
        if (ex != null || response.getStatus() >= 400 || READS.contains(request.getMethod())) return;
        hub.ifAvailable(h -> h.publish(TenantContext.getTenantId()));
    }
}
