package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.ExchangeRateResponse;
import com.voyra.crm.dto.JournalEntryResponse;
import com.voyra.crm.entity.JournalEntry;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.LedgerPartyType;
import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.TaxKind;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.AccountsDashboardService;
import com.voyra.crm.service.BaseCurrency;
import com.voyra.crm.service.BookingService;
import com.voyra.crm.service.ControlAccountReconciliationService;
import com.voyra.crm.service.CustomerLedgerService;
import com.voyra.crm.service.ExchangeRateService;
import com.voyra.crm.service.FinancialStatementsService;
import com.voyra.crm.service.GeneralLedgerQueryService;
import com.voyra.crm.service.JournalService;
import com.voyra.crm.service.OpeningBalanceBackfillService;
import com.voyra.crm.service.PartyLedgerService;
import com.voyra.crm.service.PayablesDashboardService;
import com.voyra.crm.service.TaxConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static com.voyra.crm.controller.WebTestSupport.as;
import static com.voyra.crm.controller.WebTestSupport.json;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The accounts module's read, report and ledger endpoints (owner and accountant only). */
@WebMvcTest({AccountingChangeAlertController.class, AccountsDashboardController.class, ControlAccountReconciliationController.class,
        CustomerLedgerController.class, FinancialStatementsController.class, GeneralLedgerController.class, JournalController.class,
        PartyLedgerController.class, PayablesDashboardController.class, TaxConfigController.class, ExchangeRateLookupController.class})
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class AccountsControllersWebTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final LocalDate FROM = LocalDate.of(2026, 4, 1), TO = LocalDate.of(2026, 4, 30);

    @Autowired private MockMvc mvc;
    @MockBean private BookingService bookingService;
    @MockBean private AccountsDashboardService accountsDashboardService;
    @MockBean private ControlAccountReconciliationService reconciliationService;
    @MockBean private OpeningBalanceBackfillService backfillService;
    @MockBean private CustomerLedgerService customerLedgerService;
    @MockBean private FinancialStatementsService financialStatementsService;
    @MockBean private GeneralLedgerQueryService generalLedgerQueryService;
    @MockBean private JournalService journalService;
    @MockBean private PartyLedgerService partyLedgerService;
    @MockBean private PayablesDashboardService payablesDashboardService;
    @MockBean private TaxConfigService taxConfigService;
    @MockBean private ExchangeRateService exchangeRateService;
    @MockBean private BaseCurrency baseCurrency;

    private static LocalDate today() {
        return LocalDate.now();
    }

    @Test
    void changeAlertsAreListedAndAcknowledged() throws Exception {
        mvc.perform(get("/api/accounts/change-alerts").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(post("/api/accounts/change-alerts/A1/acknowledge").with(as(UserType.ACCOUNTANT))).andExpect(status().isNoContent());
        verify(bookingService).listUnacknowledgedChangeAlerts();
        verify(bookingService).acknowledgeChangeAlert("A1");
    }

    @Test
    void agentsAreRefusedTheAccountsModule() throws Exception {
        mvc.perform(get("/api/accounts/dashboard/summary").with(as(UserType.AGENT))).andExpect(status().isForbidden());
        mvc.perform(get("/api/accounts/financial-statements/trial-balance").with(as(UserType.AGENT))).andExpect(status().isForbidden());
        mvc.perform(get("/api/accounts/gl/accounts").with(as(UserType.AGENT))).andExpect(status().isForbidden());
        verifyNoInteractions(accountsDashboardService, financialStatementsService, generalLedgerQueryService);
    }

    @Test
    void dashboardRangesDefaultToTheLastMonthEndingToday() throws Exception {
        given(accountsDashboardService.exportGstXlsx(today().minusMonths(1), today())).willReturn(new byte[]{9});

        mvc.perform(get("/api/accounts/dashboard/gst-summary").param("from", "2026-04-01").param("to", "2026-04-30").with(as(UserType.ACCOUNTANT)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/accounts/dashboard/tcs-summary").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get("/api/accounts/dashboard/tcs-summary").param("to", "2026-04-30").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get("/api/accounts/dashboard/export/gst").with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(XLSX))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("gst-register.xlsx")))
                .andExpect(content().bytes(new byte[]{9}));

        verify(accountsDashboardService).gstSummary(FROM, TO);
        verify(accountsDashboardService).tcsSummary(today().minusMonths(1), today());
        verify(accountsDashboardService).tcsSummary(TO.minusMonths(1), TO);
    }

    @Test
    void reconciliationAndOpeningBalanceBackfill() throws Exception {
        given(backfillService.backfillCurrentTenant()).willReturn(2);

        mvc.perform(get("/api/accounts/reconciliation/control-accounts").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get("/api/accounts/reconciliation/opening-balance-backfill/preview").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(post("/api/accounts/reconciliation/opening-balance-backfill").with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountsPosted").value(2));
        mvc.perform(post("/api/accounts/reconciliation/opening-balance-backfill").with(as(UserType.ACCOUNTANT))).andExpect(status().isForbidden());

        verify(reconciliationService).reconcile();
        verify(backfillService).preview();
    }

    @Test
    void customerLedgerStatementsSummariesAndExports() throws Exception {
        given(customerLedgerService.exportOutstandingXlsx()).willReturn(new byte[]{1});
        given(customerLedgerService.exportStatementXlsx("C1", FROM, TO)).willReturn(new byte[]{2});
        given(customerLedgerService.exportStatementPdf("C1", null, null)).willReturn(new byte[]{3});
        String base = "/api/accounts/ledger";

        mvc.perform(get(base + "/clients/C1").param("from", "2026-04-01").param("to", "2026-04-30").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/clients/C1/summary").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/clients").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/outstanding").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/outstanding/export").with(as(UserType.ACCOUNTANT)))
                .andExpect(content().contentType(XLSX)).andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("ar-ageing.xlsx")));
        mvc.perform(json(post(base + "/clients/C1/opening-balance"), "{\"amount\":1500,\"asOfDate\":\"2026-04-01\"}").with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isOk());
        mvc.perform(get(base + "/clients/C1/export/statement.xlsx").param("from", "2026-04-01").param("to", "2026-04-30").with(as(UserType.ACCOUNTANT)))
                .andExpect(content().contentType(XLSX)).andExpect(content().bytes(new byte[]{2}))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("statement.xlsx")));
        mvc.perform(get(base + "/clients/C1/export/statement.pdf").with(as(UserType.ACCOUNTANT)))
                .andExpect(content().contentType(MediaType.APPLICATION_PDF)).andExpect(content().bytes(new byte[]{3}));

        verify(customerLedgerService).statement("C1", FROM, TO);
        verify(customerLedgerService).summary("C1");
        verify(customerLedgerService).summaryForAllClients();
        verify(customerLedgerService).outstanding();
        verify(customerLedgerService).openingBalance(eq("C1"), any());
    }

    @Test
    void financialStatementsDefaultTheirPeriodAndExportAsSpreadsheets() throws Exception {
        given(financialStatementsService.exportTrialBalanceXlsx(FROM, TO)).willReturn(new byte[]{1});
        given(financialStatementsService.exportProfitAndLossXlsx(today().minusMonths(1), today())).willReturn(new byte[]{2});
        given(financialStatementsService.exportBalanceSheetXlsx(today())).willReturn(new byte[]{3});
        given(financialStatementsService.exportBalanceSheetXlsx(TO)).willReturn(new byte[]{4});
        String base = "/api/accounts/financial-statements";

        mvc.perform(get(base + "/trial-balance").param("from", "2026-04-01").param("to", "2026-04-30").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/profit-and-loss").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/balance-sheet").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/balance-sheet").param("asOf", "2026-04-30").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/export/trial-balance").param("from", "2026-04-01").param("to", "2026-04-30").with(as(UserType.ACCOUNTANT)))
                .andExpect(content().contentType(XLSX)).andExpect(content().bytes(new byte[]{1}))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("trial-balance.xlsx")));
        mvc.perform(get(base + "/export/profit-and-loss").with(as(UserType.ACCOUNTANT))).andExpect(content().bytes(new byte[]{2}));
        mvc.perform(get(base + "/export/balance-sheet").with(as(UserType.ACCOUNTANT))).andExpect(content().bytes(new byte[]{3}));
        mvc.perform(get(base + "/export/balance-sheet").param("asOf", "2026-04-30").with(as(UserType.ACCOUNTANT))).andExpect(content().bytes(new byte[]{4}));

        verify(financialStatementsService).trialBalance(FROM, TO);
        verify(financialStatementsService).profitAndLoss(today().minusMonths(1), today());
        verify(financialStatementsService).balanceSheet(today());
        verify(financialStatementsService).balanceSheet(TO);
    }

    @Test
    void generalLedgerChartLedgerAndJournalRegister() throws Exception {
        String base = "/api/accounts/gl";
        mvc.perform(get(base + "/accounts").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/accounts").param("asOf", "2026-04-30").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/accounts/1100/ledger").param("from", "2026-04-01").param("to", "2026-04-30").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/journals").param("sourceType", JournalSourceType.values()[0].name()).param("sourceId", "S1")
                .with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());

        verify(generalLedgerQueryService).chartOfAccounts(today());
        verify(generalLedgerQueryService).chartOfAccounts(TO);
        verify(generalLedgerQueryService).accountLedger("1100", FROM, TO);
        verify(generalLedgerQueryService).journalRegister(today().minusMonths(1), today(), JournalSourceType.values()[0], "S1");
    }

    @Test
    void manualJournalsArePostedReadAndReversed() throws Exception {
        JournalEntry reversed = new JournalEntry();
        given(journalService.reverseManual("J1", "typo")).willReturn(reversed);
        given(journalService.toResponse(reversed)).willReturn(JournalEntryResponse.builder().id("J2").build());
        String body = "{\"entryDate\":\"2026-04-01\",\"narration\":\"Rent\",\"lines\":["
                + "{\"accountCode\":\"6100\",\"debitAmount\":100,\"creditAmount\":0},"
                + "{\"accountCode\":\"1100\",\"debitAmount\":0,\"creditAmount\":100}]}";

        mvc.perform(json(post("/api/accounts/journals"), body).with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get("/api/accounts/journals/J1").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(post("/api/accounts/journals/J1/reverse").param("reason", "typo").with(as(UserType.ACCOUNTANT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("J2"));

        verify(journalService).postManual(any());
        verify(journalService).get("J1");
    }

    @Test
    void partyLedgerPassesItsFilters() throws Exception {
        mvc.perform(get("/api/accounts/ledger/entries").param("partyType", LedgerPartyType.values()[0].name()).param("partyId", "P1")
                .param("from", "2026-04-01").param("to", "2026-04-30").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        verify(partyLedgerService).entries(LedgerPartyType.values()[0], "P1", FROM, TO);
    }

    @Test
    void payablesDashboardRegistersReconciliationAndExports() throws Exception {
        given(payablesDashboardService.exportPurchaseRegisterXlsx(today().minusMonths(1), today())).willReturn(new byte[]{5});
        given(payablesDashboardService.exportCostReconciliationXlsx()).willReturn(new byte[]{6});
        String base = "/api/accounts/payables/dashboard";

        mvc.perform(get(base + "/summary").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/purchase-register").param("from", "2026-04-01").param("to", "2026-04-30").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/export/purchase-register").with(as(UserType.ACCOUNTANT)))
                .andExpect(content().contentType(XLSX)).andExpect(content().bytes(new byte[]{5}))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("purchase-register.xlsx")));
        mvc.perform(get(base + "/cost-reconciliation").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/cost-reconciliation/export").with(as(UserType.ACCOUNTANT)))
                .andExpect(content().bytes(new byte[]{6}))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("cost-reconciliation.xlsx")));

        verify(payablesDashboardService).summary();
        verify(payablesDashboardService).purchaseRegister(FROM, TO);
        verify(payablesDashboardService).costReconciliation();
    }

    @Test
    void taxRatesAreListedCreatedReplacedAndToggled() throws Exception {
        String body = "{\"taxKind\":\"" + TaxKind.values()[0] + "\",\"label\":\"GST 18\",\"supplyNature\":\"" + SupplyNature.values()[0]
                + "\",\"ratePercent\":18,\"effectiveFrom\":\"2026-04-01\"}";
        String base = "/api/accounts/tax-config";

        mvc.perform(get(base).param("taxKind", TaxKind.values()[0].name()).with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(json(post(base), body).with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(json(put(base + "/R1"), body).with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(json(patch(base + "/R1/status"), "{\"isActive\":false}").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/states").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk()).andExpect(jsonPath("$['27']").exists());

        verify(taxConfigService).list(TaxKind.values()[0]);
        verify(taxConfigService).create(any());
        verify(taxConfigService).replace(eq("R1"), any());
        verify(taxConfigService).updateStatus("R1", false);
    }

    @Test
    void latestRateDefaultsToTheBaseCurrencyAndTodayAndIsEmptyWhenUnknown() throws Exception {
        given(baseCurrency.code()).willReturn("INR");
        given(exchangeRateService.latest("USD", "INR", today()))
                .willReturn(Optional.of(ExchangeRateResponse.builder().baseCurrency("USD").quoteCurrency("INR").rate(new BigDecimal("83.10")).build()));
        given(exchangeRateService.latest("EUR", "GBP", FROM)).willReturn(Optional.empty());

        mvc.perform(get("/api/exchange-rates/latest").param("base", "USD").with(as(UserType.ACCOUNTANT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rate").value(83.10));
        mvc.perform(get("/api/exchange-rates/latest").param("base", "EUR").param("quote", "GBP").param("date", "2026-04-01").with(as(UserType.ACCOUNTANT)))
                .andExpect(status().isNoContent());
    }
}
