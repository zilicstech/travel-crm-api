package com.voyra.crm.service;

import com.voyra.crm.entity.LedgerAccount;
import com.voyra.crm.enums.InvoiceServiceCategory;
import com.voyra.crm.enums.SystemAccount;
import com.voyra.crm.repository.LedgerAccountRepository;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Seeds (and re-syncs) the CURRENT tenant's chart of accounts - every {@link SystemAccount} plus
 * one sales and one purchase account per {@link InvoiceServiceCategory} (Rule 1.4). Upserts by
 * {@code code}; never deletes a row (Rule 1.4.2) - an account that has been posted to can only be
 * deactivated, which this method never does either.
 *
 * <p>Always runs in a brand-new transaction (Rule applies regardless of caller): the startup
 * runner has no enclosing transaction, so REQUIRES_NEW behaves exactly like REQUIRED there; a
 * runtime caller such as {@code AgencyService#createAgency} IS already inside a public-schema
 * transaction when the new tenant is provisioned, and self-invoking a plain {@code @Transactional}
 * method on the same bean would bypass the Spring proxy and silently run in that transaction
 * against the WRONG schema (blueprint §3.5) - this must live on its own bean for that reason, and
 * every caller is responsible for setting {@link com.voyra.crm.context.TenantContext} first.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChartOfAccountsSeedService {

    private final LedgerAccountRepository ledgerAccountRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void seed() {
        int created = 0;
        for (SystemAccount account : SystemAccount.values()) {
            if (upsert(account.code(), account.accountName(), account.accountType(),
                    account.parentCode(), true, account.isControl(), account.controlOf())) {
                created++;
            }
        }
        for (InvoiceServiceCategory category : InvoiceServiceCategory.values()) {
            if (upsert(SystemAccount.salesCode(category), "Sales A/c (" + category.documentTitle() + ")",
                    com.voyra.crm.enums.LedgerAccountType.INCOME, SystemAccount.SALES.code(), true, false, null)) {
                created++;
            }
            if (upsert(SystemAccount.purchaseCode(category), "Purchase A/c (" + category.documentTitle() + ")",
                    com.voyra.crm.enums.LedgerAccountType.EXPENSE, SystemAccount.PURCHASES.code(), true, false, null)) {
                created++;
            }
        }
        if (created > 0) {
            log.info("Chart of accounts seeded: {} account(s) created", created);
        }
    }

    /** Returns true only when a new row was inserted - an existing account is left exactly as it is, including if a human has since renamed it. */
    private boolean upsert(String code, String name, com.voyra.crm.enums.LedgerAccountType type, String parentCode,
                            boolean isSystem, boolean isControl, com.voyra.crm.enums.ControlAccountOf controlOf) {
        if (ledgerAccountRepository.existsByCode(code)) {
            return false;
        }
        LedgerAccount account = LedgerAccount.builder()
                .id(UniqueIdResolver.resolve(ledgerAccountRepository::existsById))
                .code(code)
                .name(name)
                .accountType(type)
                .parentCode(parentCode)
                .isSystem(isSystem)
                .isControl(isControl)
                .controlOf(controlOf)
                .isActive(true)
                .createdDate(LocalDateTime.now())
                .build();
        ledgerAccountRepository.save(account);
        return true;
    }
}
