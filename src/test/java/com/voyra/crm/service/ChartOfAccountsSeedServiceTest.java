package com.voyra.crm.service;

import com.voyra.crm.enums.InvoiceServiceCategory;
import com.voyra.crm.enums.SystemAccount;
import com.voyra.crm.repository.LedgerAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChartOfAccountsSeedServiceTest {

    @Mock
    private LedgerAccountRepository ledgerAccountRepository;

    @InjectMocks
    private ChartOfAccountsSeedService chartOfAccountsSeedService;

    @Test
    void seedsEverySystemAccountAndOneSalesAndPurchaseAccountPerCategory() {
        when(ledgerAccountRepository.existsByCode(anyString())).thenReturn(false);
        when(ledgerAccountRepository.existsById(anyString())).thenReturn(false);

        chartOfAccountsSeedService.seed();

        ArgumentCaptor<com.voyra.crm.entity.LedgerAccount> captor = ArgumentCaptor.forClass(com.voyra.crm.entity.LedgerAccount.class);
        verify(ledgerAccountRepository, org.mockito.Mockito.times(
                SystemAccount.values().length + InvoiceServiceCategory.values().length * 2)).save(captor.capture());

        Set<String> codes = new HashSet<>();
        for (com.voyra.crm.entity.LedgerAccount a : captor.getAllValues()) {
            codes.add(a.getCode());
        }
        for (SystemAccount sa : SystemAccount.values()) {
            assertThat(codes).contains(sa.code());
        }
        for (InvoiceServiceCategory cat : InvoiceServiceCategory.values()) {
            assertThat(codes).contains(SystemAccount.salesCode(cat));
            assertThat(codes).contains(SystemAccount.purchaseCode(cat));
        }
        assertThat(codes).hasSize(SystemAccount.values().length + InvoiceServiceCategory.values().length * 2);
    }

    @Test
    void isIdempotentAndNeverDeletes() {
        when(ledgerAccountRepository.existsByCode(anyString())).thenReturn(true);

        chartOfAccountsSeedService.seed();

        verify(ledgerAccountRepository, never()).save(any());
        verify(ledgerAccountRepository, never()).delete(any());
        verify(ledgerAccountRepository, never()).deleteById(anyString());
    }
}
