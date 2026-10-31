package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.enums.BookingDocumentType;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.CancellationType;
import com.voyra.crm.enums.PaymentStatus;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.BookingListFilter;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.BookingCancellationService;
import com.voyra.crm.service.BookingDocumentService;
import com.voyra.crm.service.BookingLogService;
import com.voyra.crm.service.BookingService;
import com.voyra.crm.service.FeedbackLinkService;
import org.junit.jupiter.api.Test;
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

import static com.voyra.crm.controller.WebTestSupport.FILE;
import static com.voyra.crm.controller.WebTestSupport.as;
import static com.voyra.crm.controller.WebTestSupport.bytes;
import static com.voyra.crm.controller.WebTestSupport.json;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({BookingController.class, BookingDocumentController.class})
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class BookingControllersWebTest {

    private static final String B = "/api/bookings";

    @Autowired private MockMvc mvc;
    @MockBean private BookingService bookingService;
    @MockBean private BookingLogService bookingLogService;
    @MockBean private BookingCancellationService cancellationService;
    @MockBean private FeedbackLinkService feedbackLinkService;
    @MockBean private BookingDocumentService documentService;

    @Test
    void agentsCreateUpdateAndDeleteBookings() throws Exception {
        mvc.perform(json(post(B), "{\"clientId\":\"C1\",\"type\":\"" + BookingType.values()[0] + "\",\"destination\":\"Goa\"}")
                .with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(put(B + "/B1"), "{\"pnr\":\"ABC123\"}").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(patch(B + "/B1/status"), "{\"bookingStatus\":\"" + BookingStatus.values()[0] + "\"}").with(as(UserType.AGENT)))
                .andExpect(status().isOk());
        mvc.perform(json(patch(B + "/B1/payment-status"), "{\"paymentStatus\":\"" + PaymentStatus.values()[0] + "\"}").with(as(UserType.AGENT)))
                .andExpect(status().isOk());
        mvc.perform(json(patch(B + "/B1/deadlines"), "{}").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(post(B + "/B1/feedback-link").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(get(B + "/B1/cost-components").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(put(B + "/B1/cost-components"), "[{\"serviceType\":\"" + ServiceType.values()[0] + "\",\"netCost\":100}]")
                .with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(delete(B + "/B1").with(as(UserType.AGENCY_OWNER))).andExpect(status().isNoContent());

        verify(bookingService).createBooking(any());
        verify(bookingService).updateBooking(eq("B1"), any());
        verify(bookingService).updateStatus(eq("B1"), any());
        verify(bookingService).updatePaymentStatus(eq("B1"), any());
        verify(bookingService).updateDeadlines(eq("B1"), any());
        verify(feedbackLinkService).generateLink("B1");
        verify(bookingService).listCostComponents("B1");
        verify(bookingService).replaceCostComponents(eq("B1"), anyList());
        verify(bookingService).deleteBooking("B1");
    }

    @Test
    void anAccountantCannotChangeABooking() throws Exception {
        mvc.perform(delete(B + "/B1").with(as(UserType.ACCOUNTANT))).andExpect(status().isForbidden());
        mvc.perform(json(patch(B + "/B1/deadlines"), "{}").with(as(UserType.ACCOUNTANT))).andExpect(status().isForbidden());
        verifyNoInteractions(bookingService);
    }

    @Test
    void theListIsAPlainArrayWithoutAPageAndFilteredWithOne() throws Exception {
        mvc.perform(get(B).param("type", BookingType.values()[0].name()).param("destination", "Goa").with(as(UserType.ACCOUNTANT)))
                .andExpect(status().isOk());
        mvc.perform(get(B).param("page", "0").param("size", "10").param("q", "BK42").param("moneyPending", "true")
                        .param("bookedFrom", "2026-04-01").param("clientId", "C1").param("remark", "HAS").with(as(UserType.AGENT)))
                .andExpect(status().isOk());
        mvc.perform(get(B + "/unbilled").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());

        verify(bookingService).listBookings(BookingType.values()[0], null, "Goa", null, null);
        verify(bookingService).listBookings(null, null, null, null, null, "BK42", null, true,
                new BookingListFilter(LocalDate.of(2026, 4, 1), null, null, null, "C1", null, null, null, null, "HAS"), PageRequest.of(0, 10));
        verify(bookingService).listUnbilledBookings();
    }

    @Test
    void cancellationPreviewCancelAndRefundSettlement() throws Exception {
        String refund = "{\"amount\":100,\"paidOn\":\"2026-04-02\"}";
        mvc.perform(get(B + "/B1/cancel-preview").param("airlineCharge", "10").param("agencyFee", "5").with(as(UserType.AGENT)))
                .andExpect(status().isOk());
        mvc.perform(json(post(B + "/B1/cancel"), "{\"type\":\"" + CancellationType.values()[0] + "\"}").with(as(UserType.AGENT)))
                .andExpect(status().isOk());
        mvc.perform(get(B + "/refunds").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(post(B + "/B1/refunds/supplier/reject").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(json(post(B + "/B1/refunds/supplier"), refund).with(as(UserType.AGENT))).andExpect(status().isOk());

        verify(cancellationService).preview("B1", BigDecimal.TEN, null, new BigDecimal("5"));
        verify(cancellationService).cancel(eq("B1"), any());
        verify(cancellationService).listOutstanding();
        verify(cancellationService).rejectSupplierRefund("B1");
        verify(cancellationService).recordSupplierRefund(eq("B1"), any());
    }

    @Test
    void vouchersAreAddedListedUpdatedChargedAndDeleted() throws Exception {
        String base = B + "/B1/documents";
        String body = "{\"docType\":\"" + BookingDocumentType.values()[0] + "\",\"referenceNumber\":\"VCH-1\"}";
        mvc.perform(json(post(base), body).with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get(base).with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(json(put(base + "/D1"), body).with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(put(base + "/D1/charges"), "{}").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(delete(base + "/D1").with(as(UserType.AGENT))).andExpect(status().isNoContent());
        mvc.perform(multipart(base + "/D1/file").file(FILE).with(as(UserType.AGENT))).andExpect(status().isOk());

        verify(documentService).addDocument(eq("B1"), any());
        verify(documentService).listForBooking("B1");
        verify(documentService).updateDocument(eq("B1"), eq("D1"), any());
        verify(documentService).setCharges(eq("B1"), eq("D1"), any());
        verify(documentService).deleteDocument("B1", "D1");
        verify(documentService).attachFile(eq("B1"), eq("D1"), any());
    }

    @Test
    void aVoucherFileIsServedInlineWithItsTypeOrAsOctetStream() throws Exception {
        given(documentService.downloadFile("B1", "D1")).willReturn(new BookingDocumentService.BookingDocumentFileContent(bytes("t"), "t.pdf", "application/pdf"));
        given(documentService.downloadFile("B1", "D2")).willReturn(new BookingDocumentService.BookingDocumentFileContent(bytes("u"), "u.bin", null));

        mvc.perform(get(B + "/B1/documents/D1/file").with(as(UserType.ACCOUNTANT)))
                .andExpect(content().contentType(MediaType.APPLICATION_PDF)).andExpect(content().string("t"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"t.pdf\""));
        mvc.perform(get(B + "/B1/documents/D2/file").with(as(UserType.AGENT))).andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM));
    }
}
