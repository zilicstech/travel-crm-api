package com.voyra.crm.enums;

/**
 * Fixed-code system accounts seeded into every tenant's chart of accounts, per
 * ACCOUNTING_EXPANSION_ARCHITECTURE.md §1.3. A service never hardcodes a {@code ledger_account.id}
 * - it hardcodes a constant from here, in exactly one place, and resolves the row through
 * {@code service.LedgerAccountResolver}.
 *
 * <p>Per-{@link InvoiceServiceCategory} sales (<code>40xx</code>) and purchase (<code>50xx</code>)
 * accounts are not enumerated here - there are eight of each, one per category, seeded by
 * {@code ChartOfAccountsSeedService} and resolved by category rather than by a fixed code.
 */
public enum SystemAccount {

    CASH_IN_HAND("1100", "Cash in Hand", LedgerAccountType.ASSET, null, false, null),
    BANK_ACCOUNTS("1110", "Bank Accounts", LedgerAccountType.ASSET, null, false, null),
    ACCOUNTS_RECEIVABLE("1200", "Accounts Receivable", LedgerAccountType.ASSET, null, true, ControlAccountOf.ACCOUNTS_RECEIVABLE),
    SUPPLIER_ADVANCES("1300", "Supplier Advances", LedgerAccountType.ASSET, null, true, ControlAccountOf.SUPPLIER_ADVANCES),
    INPUT_GST_RECEIVABLE("1400", "Input GST Receivable", LedgerAccountType.ASSET, null, false, null),

    CLIENT_ADVANCES_HELD("2110", "Client Advances Held", LedgerAccountType.LIABILITY, null, true, ControlAccountOf.CLIENT_ADVANCES),
    UNEARNED_TOUR_REVENUE("2120", "Unearned Tour Revenue", LedgerAccountType.LIABILITY, null, true, ControlAccountOf.CLIENT_UNEARNED),
    ACCOUNTS_PAYABLE("2200", "Accounts Payable", LedgerAccountType.LIABILITY, null, true, ControlAccountOf.ACCOUNTS_PAYABLE),
    CLIENT_PASS_THROUGH_PAYABLE("2210", "Client Pass-Through Payable", LedgerAccountType.LIABILITY, null, false, null),
    OUTPUT_GST_PAYABLE("2310", "Output GST Payable", LedgerAccountType.LIABILITY, null, false, null),
    TCS_PAYABLE("2320", "TCS Payable", LedgerAccountType.LIABILITY, null, false, null),

    OPENING_BALANCE_EQUITY("3100", "Opening Balance Equity", LedgerAccountType.EQUITY, null, false, null),
    RETAINED_EARNINGS("3200", "Retained Earnings", LedgerAccountType.EQUITY, null, false, null),

    /**
     * Grouping headers only - never posted to directly. {@code ChartOfAccountsSeedService} parents
     * the eight per-category {@code salesCode}/{@code purchaseCode} accounts under these so the
     * Trial Balance's parent-code tree (architecture §1.9) actually nests instead of listing all
     * sixteen flat. Must sort before the 40xx/50xx range they parent (enum declaration order is
     * seed order, and a child referencing a not-yet-inserted parent is still fine since this is a
     * plain FK-less string column, but keeping the header first reads correctly either way).
     */
    SALES("4000", "Sales", LedgerAccountType.INCOME, null, false, null),
    PURCHASES("5000", "Purchases", LedgerAccountType.EXPENSE, null, false, null),

    SERVICE_FEE_INCOME("4600", "Service Fee / Commission Income", LedgerAccountType.INCOME, null, false, null),
    REALIZED_FOREX_GAIN("4700", "Realized Forex Gain", LedgerAccountType.INCOME, null, false, null),
    UNREALIZED_FOREX_GAIN("4710", "Unrealized Forex Gain", LedgerAccountType.INCOME, null, false, null),

    PAYMENT_PROCESSING_FEES("5610", "Payment Processing Fees", LedgerAccountType.EXPENSE, null, false, null),
    BANK_CHARGES("5620", "Bank Charges", LedgerAccountType.EXPENSE, null, false, null),
    REALIZED_FOREX_LOSS("5700", "Realized Forex Loss", LedgerAccountType.EXPENSE, null, false, null),
    UNREALIZED_FOREX_LOSS("5710", "Unrealized Forex Loss", LedgerAccountType.EXPENSE, null, false, null);

    private final String code;
    private final String accountName;
    private final LedgerAccountType accountType;
    private final String parentCode;
    private final boolean control;
    private final ControlAccountOf controlOf;

    SystemAccount(String code, String accountName, LedgerAccountType accountType, String parentCode,
                  boolean control, ControlAccountOf controlOf) {
        this.code = code;
        this.accountName = accountName;
        this.accountType = accountType;
        this.parentCode = parentCode;
        this.control = control;
        this.controlOf = controlOf;
    }

    public String code() {
        return code;
    }

    public String accountName() {
        return accountName;
    }

    public LedgerAccountType accountType() {
        return accountType;
    }

    public String parentCode() {
        return parentCode;
    }

    public boolean isControl() {
        return control;
    }

    public ControlAccountOf controlOf() {
        return controlOf;
    }

    /** First digit of the code's account-type block, e.g. {@code "4010"} -&gt; sales/income base. */
    public static String salesCode(InvoiceServiceCategory category) {
        return "40%02d".formatted((categoryIndex(category) + 1) * 10);
    }

    public static String purchaseCode(InvoiceServiceCategory category) {
        return "50%02d".formatted((categoryIndex(category) + 1) * 10);
    }

    private static int categoryIndex(InvoiceServiceCategory category) {
        InvoiceServiceCategory[] values = InvoiceServiceCategory.values();
        for (int i = 0; i < values.length; i++) {
            if (values[i] == category) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unknown InvoiceServiceCategory: " + category);
    }
}
