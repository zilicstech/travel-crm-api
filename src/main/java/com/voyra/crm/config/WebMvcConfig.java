package com.voyra.crm.config;

import com.voyra.crm.security.EntitlementInterceptor;
import com.voyra.crm.security.TenantStatusInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final TenantStatusInterceptor tenantStatusInterceptor;
    private final EntitlementInterceptor entitlementInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Order matters: a SUSPENDED agency gets its 423 before any 402 subscription refusal.
        registry.addInterceptor(tenantStatusInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(entitlementInterceptor).addPathPatterns("/api/**");
    }
}
