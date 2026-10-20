package com.voyra.crm.service;

import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.BookingPassengerRepository;
import com.voyra.crm.repository.BookingSectorRepository;
import com.voyra.crm.repository.InvoiceLineItemRepository;
import com.voyra.crm.repository.InvoiceRepository;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;

/** A real InvoiceLineSource over a test's own repository mocks (a fresh mock for any it doesn't have). */
final class TestInvoiceLines {

    private TestInvoiceLines() {
    }

    static InvoiceLineSource over(InvoiceRepository invoices, InvoiceLineItemRepository lines, BookingDocumentRepository vouchers) {
        return new InvoiceLineSource(
                invoices != null ? invoices : mock(InvoiceRepository.class),
                lines != null ? lines : mock(InvoiceLineItemRepository.class),
                vouchers != null ? vouchers : mock(BookingDocumentRepository.class),
                mock(BookingPassengerRepository.class), mock(BookingSectorRepository.class));
    }

    static void into(Object service, InvoiceRepository invoices, InvoiceLineItemRepository lines, BookingDocumentRepository vouchers) {
        ReflectionTestUtils.setField(service, "invoiceLineSource", over(invoices, lines, vouchers));
    }
}
