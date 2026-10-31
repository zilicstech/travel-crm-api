package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.CreditNoteResponse;
import com.voyra.crm.dto.InvoiceResponse;
import com.voyra.crm.dto.PaymentReceiptResponse;
import com.voyra.crm.dto.SupplierPaymentResponse;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.SupplierCreditNoteReason;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.TaxTreatment;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.TaxComputationRequest;
import com.voyra.crm.models.TaxComputationResult;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.BaseCurrency;
import com.voyra.crm.service.CreditNoteService;
import com.voyra.crm.service.InvoiceDocumentService;
import com.voyra.crm.service.InvoicePurchaseService;
import com.voyra.crm.service.InvoiceService;
import com.voyra.crm.service.PaymentReceiptService;
import com.voyra.crm.service.SupplierCreditNoteService;
import com.voyra.crm.service.SupplierInvoiceService;
import com.voyra.crm.service.SupplierLedgerService;
import com.voyra.crm.service.SupplierPaymentService;
import com.voyra.crm.service.TaxEngine;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.voyra.crm.controller.WebTestSupport.FILE;
import static com.voyra.crm.controller.WebTestSupport.as;
import static com.voyra.crm.controller.WebTestSupport.bytes;
import static com.voyra.crm.controller.WebTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Customer invoices, receipts and credit notes, and the supplier bills, payments and ledger. */
@WebMvcTest({CreditNoteController.class, InvoiceController.class, InvoiceDocumentController.class, PaymentReceiptController.class,
        SupplierCreditNoteController.class, SupplierInvoiceController.class, SupplierLedgerController.class, SupplierPaymentController.class})
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class MoneyDocumentControllersWebTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final LocalDate FROM = LocalDate.of(2026, 4, 1), TO = LocalDate.of(2026, 4, 30);
    private static final String REASON = "{\"reason\":\"Entered twice\"}";

    @Autowired private MockMvc mvc;
    @MockBean private CreditNoteService creditNoteService;
    @MockBean private InvoiceService invoiceService;
    @MockBean private InvoiceDocumentService invoiceDocumentService;
    @MockBean private InvoicePurchaseService invoicePurchaseService;
    @MockBean private TaxEngine taxEngine;
    @MockBean private BaseCurrency baseCurrency;
    @MockBean private PaymentReceiptService paymentReceiptService;
    @MockBean private SupplierCreditNoteService supplierCreditNoteService;
    @MockBean private SupplierInvoiceService supplierInvoiceService;
    @MockBean private SupplierLedgerService supplierLedgerService;
    @MockBean private SupplierPaymentService supplierPaymentService;

    @Test
    void creditNotesAreCreatedListedIssuedCancelledAndRefunded() throws Exception {
        String base = "/api/accounts/credit-notes";
        mvc.perform(json(post(base), "{\"invoiceId\":\"I1\",\"reason\":\"" + com.voyra.crm.enums.CreditNoteReason.values()[0] + "\"}")
                .with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base).param("invoiceId", "I1").param("page", "0").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/CN1").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(post(base + "/CN1/issue").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(json(post(base + "/CN1/cancel"), REASON).with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(json(post(base + "/CN1/refund"), "{\"amount\":100,\"paymentMode\":\"" + PaymentMode.values()[0]
                + "\",\"receivedOn\":\"2026-04-02\"}").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());

        verify(creditNoteService).create(any());
        verify(creditNoteService).list(eq("I1"), isNull(), org.mockito.ArgumentMatchers.argThat(p -> p.getPageNumber() == 0));
        verify(creditNoteService).get("CN1");
        verify(creditNoteService).issue("CN1");
        verify(creditNoteService).cancel("CN1", "Entered twice");
        verify(creditNoteService).refund(eq("CN1"), any());
    }

    @Test
    void aDraftCreditNotePdfIsNamedFromItsId() throws Exception {
        given(creditNoteService.get("abcdefgh-1234")).willReturn(CreditNoteResponse.builder().build());
        given(creditNoteService.getPdf("abcdefgh-1234")).willReturn(new byte[]{7});
        given(creditNoteService.get("CN2")).willReturn(CreditNoteResponse.builder().creditNoteNumber("CN/2026/1").build());
        given(creditNoteService.getPdf("CN2")).willReturn(new byte[]{8});

        mvc.perform(get("/api/accounts/credit-notes/abcdefgh-1234/pdf").with(as(UserType.ACCOUNTANT)))
                .andExpect(content().contentType(MediaType.APPLICATION_PDF)).andExpect(content().bytes(new byte[]{7}))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("DRAFT-abcdefgh.pdf")));
        mvc.perform(get("/api/accounts/credit-notes/CN2/pdf").with(as(UserType.ACCOUNTANT)))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("CN-2026-1.pdf")));
    }

    @Test
    void clientInvoicesAreAPlainListWithoutAPageAndAnEnvelopeWithOne() throws Exception {
        mvc.perform(get("/api/invoices/client").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get("/api/invoices/client").param("page", "1").param("size", "5").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get("/api/invoices/summary").with(as(UserType.AGENT))).andExpect(status().isOk());
        verify(invoiceService).listClientInvoices();
        verify(invoiceService).listClientInvoices(PageRequest.of(1, 5));
        verify(invoiceService).getSummary();
    }

    @Test
    void invoiceDraftsAreCreatedUpdatedIssuedAndCancelled() throws Exception {
        String base = "/api/accounts/invoices";
        mvc.perform(json(post(base), "{\"bookingId\":\"B1\"}").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(json(post(base + "/drafts"), "{\"bookingIds\":[\"B1\",\"B2\"]}").with(as(UserType.ACCOUNTANT))).andExpect(status().isCreated());
        mvc.perform(get(base).param("status", InvoiceLifecycle.values()[0].name()).param("clientId", "C1").param("from", "2026-04-01")
                .with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get(base + "/I1").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(put(base + "/I1"), "{\"notes\":\"n\"}").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(delete(base + "/I1").with(as(UserType.ACCOUNTANT))).andExpect(status().isNoContent());
        mvc.perform(post(base + "/I1/issue").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(json(post(base + "/I1/cancel"), REASON).with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(post(base + "/I1/issue-proforma").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(post(base + "/I1/convert-to-tax-invoice").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());

        verify(invoiceDocumentService).createDraft(any());
        verify(invoiceDocumentService).createDrafts(List.of("B1", "B2"));
        verify(invoiceDocumentService).list(InvoiceLifecycle.values()[0], "C1", null, FROM, null, null);
        verify(invoiceDocumentService).get("I1");
        verify(invoiceDocumentService).updateDraft(eq("I1"), any());
        verify(invoiceDocumentService).deleteDraft("I1");
        verify(invoicePurchaseService).issue(eq("I1"), isNull());
        verify(invoiceDocumentService).cancel("I1", "Entered twice");
        verify(invoiceDocumentService).issueProforma("I1");
        verify(invoiceDocumentService).convertToTaxInvoice("I1");
    }

    @Test
    void anIssuedInvoicePdfIsNamedFromItsNumber() throws Exception {
        given(invoiceDocumentService.get("I2")).willReturn(InvoiceResponse.builder().invoiceNumber("INV/26/9").build());
        given(invoiceDocumentService.getPdf("I2")).willReturn(new byte[]{4});
        mvc.perform(get("/api/accounts/invoices/I2/pdf").with(as(UserType.ACCOUNTANT)))
                .andExpect(content().bytes(new byte[]{4}))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("INV-26-9.pdf")));
        given(invoiceDocumentService.get("abcdefgh-2")).willReturn(InvoiceResponse.builder().build());
        mvc.perform(get("/api/accounts/invoices/abcdefgh-2/pdf").with(as(UserType.ACCOUNTANT)))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("DRAFT-abcdefgh.pdf")));
    }

    @Test
    void anUploadedInvoiceFileIsServedInlineWithItsTypeOrAsOctetStream() throws Exception {
        given(invoiceDocumentService.getUploadedFile("I1"))
                .willReturn(new InvoiceDocumentService.UploadedInvoiceFile(bytes("pdf"), "bill.pdf", "application/pdf"));
        given(invoiceDocumentService.getUploadedFile("I2"))
                .willReturn(new InvoiceDocumentService.UploadedInvoiceFile(bytes("raw"), "bill.bin", null));

        mvc.perform(get("/api/accounts/invoices/I1/file").with(as(UserType.ACCOUNTANT)))
                .andExpect(content().contentType(MediaType.APPLICATION_PDF)).andExpect(content().string("pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"bill.pdf\""));
        mvc.perform(get("/api/accounts/invoices/I2/file").with(as(UserType.ACCOUNTANT)))
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM));
        mvc.perform(get("/api/accounts/invoices/I2/file").with(as(UserType.AGENT))).andExpect(status().isForbidden());
    }

    @Test
    void aTaxPreviewInTheInvoiceCurrencyIsComputedByTheEngine() throws Exception {
        BigDecimal r = new BigDecimal("18");
        given(taxEngine.compute(any())).willReturn(new TaxComputationResult(TaxTreatment.values()[0], "27", "9985", new BigDecimal("1000"),
                r, r, r, r, r, r, r, r, r, "206C", r, r, new BigDecimal("1180")));

        mvc.perform(json(post("/api/accounts/invoices/preview-tax"), "{\"clientId\":\"C1\",\"taxableAmount\":1000,\"supplyNature\":\""
                        + SupplyNature.values()[0] + "\",\"currencyCode\":\"USD\",\"exportOfServiceRequested\":true}").with(as(UserType.ACCOUNTANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeOfSupplyCode").value("27"))
                .andExpect(jsonPath("$.tcsSection").value("206C"))
                .andExpect(jsonPath("$.grandTotal").value(1180));

        ArgumentCaptor<TaxComputationRequest> sent = ArgumentCaptor.forClass(TaxComputationRequest.class);
        verify(taxEngine).compute(sent.capture());
        assertThat(sent.getValue().currencyCode()).isEqualTo("USD");
        assertThat(sent.getValue().exportOfServiceRequested()).isTrue();
        verifyNoInteractions(baseCurrency);
    }

    @Test
    void receiptsAreRecordedAppliedReversedAndPrinted() throws Exception {
        String base = "/api/accounts/receipts";
        given(paymentReceiptService.get("abcdefgh-9")).willReturn(PaymentReceiptResponse.builder().build());
        given(paymentReceiptService.get("R2")).willReturn(PaymentReceiptResponse.builder().receiptNumber("RCT/1").build());
        given(paymentReceiptService.getPdf(any())).willReturn(new byte[]{1});

        mvc.perform(json(post(base), "{\"amount\":100,\"paymentMode\":\"" + PaymentMode.values()[0] + "\",\"receivedOn\":\"2026-04-02\"}")
                .with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(json(post(base + "/invoices/I1/apply-advance"), "{\"advanceReceiptId\":\"R0\",\"amount\":50}").with(as(UserType.ACCOUNTANT)))
                .andExpect(status().isOk());
        mvc.perform(get(base + "/invoices/I1/wallet").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base).param("clientId", "C1").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get(base + "/R2").with(as(UserType.AGENT))).andExpect(status().isOk()).andExpect(jsonPath("$.receiptNumber").value("RCT/1"));
        mvc.perform(json(post(base + "/R1/reverse"), REASON).with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/abcdefgh-9/pdf").with(as(UserType.AGENT)))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("DRAFT-abcdefgh.pdf")));
        mvc.perform(get(base + "/R2/pdf").with(as(UserType.AGENT)))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("RCT-1.pdf")));

        verify(paymentReceiptService).record(any());
        verify(paymentReceiptService).applyAdvance(eq("I1"), any());
        verify(paymentReceiptService).walletFor("I1");
        verify(paymentReceiptService).list(null, "C1", null);
        verify(paymentReceiptService).reverse("R1", "Entered twice");
    }

    @Test
    void supplierCreditNotesAreRecordedReadCancelledAndRefunded() throws Exception {
        String base = "/api/accounts/payables/credit-notes";
        mvc.perform(json(post(base), "{\"supplierInvoiceId\":\"SI1\",\"reason\":\"" + SupplierCreditNoteReason.values()[0]
                + "\",\"taxableValue\":100,\"cgstAmount\":9,\"sgstAmount\":9,\"igstAmount\":0}").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/SCN1").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base).param("vendorId", "V1").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(json(post(base + "/SCN1/cancel"), REASON).with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(json(post(base + "/SCN1/refund"), "{\"amount\":10,\"paymentMode\":\"" + PaymentMode.values()[0]
                + "\",\"receivedOn\":\"2026-04-02\"}").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());

        verify(supplierCreditNoteService).record(any());
        verify(supplierCreditNoteService).get("SCN1");
        verify(supplierCreditNoteService).listForVendor("V1");
        verify(supplierCreditNoteService).cancel("SCN1", "Entered twice");
        verify(supplierCreditNoteService).receiveRefund(eq("SCN1"), any());
    }

    @Test
    void supplierBillsAreDraftedApprovedCancelledAndCarryAFile() throws Exception {
        String base = "/api/accounts/payables/invoices";
        String body = "{\"vendorId\":\"V1\",\"category\":\"" + BookingType.values()[0] + "\",\"invoiceDate\":\"2026-04-01\"}";
        given(supplierInvoiceService.downloadFile("SI1")).willReturn(new SupplierInvoiceService.SupplierInvoiceFileContent(bytes("x"), "b.png", "image/png"));
        given(supplierInvoiceService.downloadFile("SI2")).willReturn(new SupplierInvoiceService.SupplierInvoiceFileContent(bytes("y"), "b.bin", null));

        mvc.perform(json(post(base), body).with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base).param("vendorId", "V1").param("status", SupplierInvoiceStatus.values()[0].name()).with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/SI1").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(json(put(base + "/SI1"), body).with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(delete(base + "/SI1").with(as(UserType.ACCOUNTANT))).andExpect(status().isNoContent());
        mvc.perform(post(base + "/SI1/approve").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(json(post(base + "/SI2/approve"), "{\"overrideReason\":\"agreed\"}").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(json(post(base + "/SI1/cancel"), REASON).with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(multipart(base + "/SI1/file").file(FILE).with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/SI1/file").with(as(UserType.ACCOUNTANT)))
                .andExpect(content().contentType(MediaType.IMAGE_PNG)).andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"b.png\""));
        mvc.perform(get(base + "/SI2/file").with(as(UserType.ACCOUNTANT))).andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM));

        verify(supplierInvoiceService).createDraft(any());
        verify(supplierInvoiceService).list("V1", SupplierInvoiceStatus.values()[0], null);
        verify(supplierInvoiceService).get("SI1");
        verify(supplierInvoiceService).updateDraft(eq("SI1"), any());
        verify(supplierInvoiceService).deleteDraft("SI1");
        verify(supplierInvoiceService).approve("SI1", null);
        verify(supplierInvoiceService).approve("SI2", "agreed");
        verify(supplierInvoiceService).cancel("SI1", "Entered twice");
        verify(supplierInvoiceService).attachFile(eq("SI1"), any());
    }

    @Test
    void supplierLedgerStatementsSummariesAndExports() throws Exception {
        String base = "/api/accounts/payables/ledger";
        given(supplierLedgerService.exportOutstandingXlsx()).willReturn(new byte[]{1});
        given(supplierLedgerService.exportStatementXlsx("V1", FROM, TO)).willReturn(new byte[]{2});
        given(supplierLedgerService.exportStatementPdf("V1", null, null)).willReturn(new byte[]{3});

        mvc.perform(get(base + "/vendors/V1").param("from", "2026-04-01").param("to", "2026-04-30").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/vendors/V1/summary").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/vendors").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/outstanding").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/outstanding/export").with(as(UserType.ACCOUNTANT)))
                .andExpect(content().contentType(XLSX)).andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("ap-ageing.xlsx")));
        mvc.perform(json(post(base + "/vendors/V1/opening-balance"), "{\"amount\":-200,\"asOfDate\":\"2026-04-01\"}").with(as(UserType.ACCOUNTANT)))
                .andExpect(status().isOk());
        mvc.perform(get(base + "/vendors/V1/export/statement.xlsx").param("from", "2026-04-01").param("to", "2026-04-30").with(as(UserType.ACCOUNTANT)))
                .andExpect(content().bytes(new byte[]{2})).andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("vendor-statement.xlsx")));
        mvc.perform(get(base + "/vendors/V1/export/statement.pdf").with(as(UserType.ACCOUNTANT)))
                .andExpect(content().contentType(MediaType.APPLICATION_PDF)).andExpect(content().bytes(new byte[]{3}));

        verify(supplierLedgerService).statement("V1", FROM, TO);
        verify(supplierLedgerService).summary("V1");
        verify(supplierLedgerService).summaryForAllVendors();
        verify(supplierLedgerService).outstanding();
        verify(supplierLedgerService).openingBalance(eq("V1"), any());
    }

    @Test
    void supplierPaymentsArePaidAppliedReversedAndAdvised() throws Exception {
        String base = "/api/accounts/payables/payments";
        given(supplierPaymentService.get("abcdefgh-1")).willReturn(SupplierPaymentResponse.builder().build());
        given(supplierPaymentService.get("SP2")).willReturn(SupplierPaymentResponse.builder().voucherNumber("PV/7").build());
        given(supplierPaymentService.getAdvicePdf(any())).willReturn(new byte[]{1});

        mvc.perform(json(post(base), "{\"vendorId\":\"V1\",\"amount\":100,\"paymentMode\":\"" + PaymentMode.values()[0]
                + "\",\"paidOn\":\"2026-04-02\"}").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(json(post(base + "/invoices/SI1/apply-advance"), "{\"advancePaymentId\":\"SP0\",\"amount\":20}").with(as(UserType.ACCOUNTANT)))
                .andExpect(status().isOk());
        mvc.perform(json(post(base + "/SP1/reverse"), REASON).with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/SP2").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base + "/abcdefgh-1/advice-pdf").with(as(UserType.ACCOUNTANT)))
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("ADVICE-abcdefgh.pdf")));
        mvc.perform(get(base + "/SP2/advice-pdf").with(as(UserType.ACCOUNTANT)))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("PV-7.pdf")));
        mvc.perform(get(base + "/vendors/V1/unallocated-advances").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());

        verify(supplierPaymentService).pay(any());
        verify(supplierPaymentService).applyAdvance(eq("SI1"), any());
        verify(supplierPaymentService).reverse("SP1", "Entered twice");
        verify(supplierPaymentService).unallocatedAdvances("V1");
    }

    @Test
    void supplierPaymentsAreListedByBillFirstThenByVendorAndNeedOneOfThem() throws Exception {
        String base = "/api/accounts/payables/payments";
        mvc.perform(get(base).param("supplierInvoiceId", "SI1").param("vendorId", "V1").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base).param("vendorId", "V1").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(base).with(as(UserType.ACCOUNTANT)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Supply vendorId or supplierInvoiceId"));

        verify(supplierPaymentService).listForInvoice("SI1");
        verify(supplierPaymentService).listForVendor("V1");
    }
}
