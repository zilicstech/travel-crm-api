package com.voyra.crm.security;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.service.AgencyEventHub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AgencyChangeInterceptorTest {

    private final AgencyEventHub hub = mock(AgencyEventHub.class);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static ObjectProvider<AgencyEventHub> provider(AgencyEventHub hub) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (hub != null) factory.addBean("hub", hub);
        return factory.getBeanProvider(AgencyEventHub.class);
    }

    private static MockHttpServletResponse status(int code) {
        MockHttpServletResponse r = new MockHttpServletResponse();
        r.setStatus(code);
        return r;
    }

    @Test
    void aSuccessfulWriteInAnAgencyTellsItsScreens() {
        TenantContext.setTenantId("T1");
        AgencyChangeInterceptor interceptor = new AgencyChangeInterceptor(provider(hub));
        interceptor.afterCompletion(new MockHttpServletRequest("POST", "/api/bookings"), status(200), new Object(), null);
        interceptor.afterCompletion(new MockHttpServletRequest("DELETE", "/api/bookings/B1"), status(204), new Object(), null);
        verify(hub, times(2)).publish("T1");
    }

    @Test
    void readsFailuresAndThrownRequestsSayNothing() {
        TenantContext.setTenantId("T1");
        AgencyChangeInterceptor interceptor = new AgencyChangeInterceptor(provider(hub));
        interceptor.afterCompletion(new MockHttpServletRequest("GET", "/api/bookings"), status(200), new Object(), null);
        interceptor.afterCompletion(new MockHttpServletRequest("HEAD", "/api/bookings"), status(200), new Object(), null);
        interceptor.afterCompletion(new MockHttpServletRequest("OPTIONS", "/api/bookings"), status(200), new Object(), null);
        interceptor.afterCompletion(new MockHttpServletRequest("POST", "/api/bookings"), status(409), new Object(), null);
        interceptor.afterCompletion(new MockHttpServletRequest("POST", "/api/bookings"), status(200), new Object(), new RuntimeException());
        verify(hub, never()).publish(any());
    }

    @Test
    void withoutAHubNothingHappens() {
        new AgencyChangeInterceptor(provider(null))
                .afterCompletion(new MockHttpServletRequest("POST", "/api/bookings"), status(200), new Object(), null);
    }
}
