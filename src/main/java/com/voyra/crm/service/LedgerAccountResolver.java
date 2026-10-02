package com.voyra.crm.service;

import com.voyra.crm.entity.LedgerAccount;
import com.voyra.crm.enums.InvoiceServiceCategory;
import com.voyra.crm.enums.SystemAccount;
import com.voyra.crm.repository.LedgerAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maps a {@link SystemAccount} - or a {@link SystemAccount} plus {@link InvoiceServiceCategory}
 * for the per-category sales/purchase accounts - to the actual {@link LedgerAccount} row in the
 * CURRENT tenant's chart of accounts (Rule 1.3.2). Deliberately uncached: {@code ledger_account}
 * is a ~34-row table read only at posting time, not on every request, and a code-keyed static
 * cache would have to be tenant-scoped to avoid leaking one agency's account ids into another's
 * request on the same JVM (blueprint §8.10 rule 2) - not worth building for a table this small
 * and this cold.
 */
@Service
@RequiredArgsConstructor
public class LedgerAccountResolver {

    private final LedgerAccountRepository ledgerAccountRepository;

    @Transactional(readOnly = true)
    public LedgerAccount resolve(SystemAccount account) {
        return findOrThrow(account.code());
    }

    @Transactional(readOnly = true)
    public LedgerAccount resolveSales(InvoiceServiceCategory category) {
        return findOrThrow(SystemAccount.salesCode(category));
    }

    @Transactional(readOnly = true)
    public LedgerAccount resolvePurchase(InvoiceServiceCategory category) {
        return findOrThrow(SystemAccount.purchaseCode(category));
    }

    private LedgerAccount findOrThrow(String code) {
        return ledgerAccountRepository.findByCode(code)
                .orElseThrow(() -> new IllegalStateException(
                        "Chart of accounts is missing code " + code + " for this tenant - the seed runner may not have run yet"));
    }
}
