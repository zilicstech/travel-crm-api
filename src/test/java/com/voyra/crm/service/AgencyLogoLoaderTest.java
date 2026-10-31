package com.voyra.crm.service;

import com.voyra.crm.entity.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgencyLogoLoaderTest {

    private final FileStorageService storage = mock(FileStorageService.class);
    private final AgencyLogoLoader loader = new AgencyLogoLoader(storage);

    private static Tenant agency(String logoKey) {
        return Tenant.builder().id("T1").logoKey(logoKey).build();
    }

    @Test
    void readsTheStoredLogo() {
        when(storage.retrieve("logo.png")).thenReturn(new ByteArrayResource(new byte[]{1, 2, 3}));
        assertThat(loader.load(agency("logo.png"))).containsExactly(1, 2, 3);
    }

    @Test
    void anUnreadableLogoIsNone() {
        when(storage.retrieve("logo.png")).thenReturn(new InputStreamResource(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("gone");
            }
        }));
        assertThat(loader.load(agency("logo.png"))).isNull();
    }

    @Test
    void noOrBlankLogoKeyNeverTouchesStorage() {
        assertThat(loader.load(agency(null))).isNull();
        assertThat(loader.load(agency(" "))).isNull();
        verify(storage, never()).retrieve(any());
    }
}
