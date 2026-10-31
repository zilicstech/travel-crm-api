package com.voyra.crm.service;

import com.voyra.crm.entity.LedgerAccount;
import com.voyra.crm.repository.LedgerAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerAccountResolverTest {

    @Mock private LedgerAccountRepository repository;
    @InjectMocks private LedgerAccountResolver resolver;

    @Test
    void theVoyraWalletIsAccount1310ByCodeAndByName() {
        when(repository.findByCode("1310")).thenReturn(Optional.of(
                LedgerAccount.builder().code("1310").name("Voyra Wallet (advance to supplier)").build()));

        assertThat(resolver.voyraWalletCode()).isEqualTo("1310");
    }

    /** A tenant whose 1310 is some other account must never take Voyra Wallet postings - the mirror fails instead. */
    @Test
    void a1310WithAnotherNameIsRefused() {
        when(repository.findByCode("1310")).thenReturn(Optional.of(LedgerAccount.builder().code("1310").name("Petty Cash").build()));

        assertThatThrownBy(() -> resolver.voyraWalletCode())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Voyra Wallet");
    }

    @Test
    void salesAndPurchaseAccountsResolveByTheirCategoryCode() {
        com.voyra.crm.entity.LedgerAccount sales = LedgerAccount.builder().code("4010").name("Sales").build();
        com.voyra.crm.entity.LedgerAccount purchase = LedgerAccount.builder().code("5010").name("Purchase").build();
        when(repository.findByCode(com.voyra.crm.enums.SystemAccount.salesCode(com.voyra.crm.enums.InvoiceServiceCategory.AIR_INTERNATIONAL)))
                .thenReturn(Optional.of(sales));
        when(repository.findByCode(com.voyra.crm.enums.SystemAccount.purchaseCode(com.voyra.crm.enums.InvoiceServiceCategory.AIR_INTERNATIONAL)))
                .thenReturn(Optional.of(purchase));

        assertThat(resolver.resolveSales(com.voyra.crm.enums.InvoiceServiceCategory.AIR_INTERNATIONAL)).isSameAs(sales);
        assertThat(resolver.resolvePurchase(com.voyra.crm.enums.InvoiceServiceCategory.AIR_INTERNATIONAL)).isSameAs(purchase);
    }

    @Test
    void aMissingAccountNamesItsCode() {
        when(repository.findByCode("1310")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.resolve(com.voyra.crm.enums.SystemAccount.VOYRA_WALLET_ADVANCE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Chart of accounts is missing code 1310 for this tenant - the seed runner may not have run yet");
    }
}
