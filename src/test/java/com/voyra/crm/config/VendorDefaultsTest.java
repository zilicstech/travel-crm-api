package com.voyra.crm.config;

import com.voyra.crm.repository.VendorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VendorDefaultsTest {

    @Mock
    private VendorRepository vendorRepository;
    @InjectMocks
    private VendorDefaults runner;

    @Test
    void seedsTheDefaultVendorsIntoAnEmptyTenant() {
        when(vendorRepository.count()).thenReturn(0L);

        assertTrue(runner.seedCurrentTenant());

        verify(vendorRepository, times(10)).save(any());
    }

    @Test
    void leavesATenantThatAlreadyHasVendorsAlone() {
        when(vendorRepository.count()).thenReturn(3L);

        assertFalse(runner.seedCurrentTenant());

        verify(vendorRepository, never()).save(any());
    }
}
