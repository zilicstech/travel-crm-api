package com.voyra.crm.service;

import com.voyra.crm.dto.InvoiceDraftRequest;
import com.voyra.crm.dto.InvoiceResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.TaxRateConfig;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.TaxKind;
import com.voyra.crm.repository.TaxRateConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingInvoiceIssuerTest {

    @Mock private InvoiceDocumentService invoiceDocumentService;
    @Mock private TaxRateConfigRepository taxRateConfigRepository;
    @InjectMocks private BookingInvoiceIssuer issuer;

    /** GST on the agency's own invoices is switched off for now (invoices come from the agency's own software); these cover it switched on. */
    @org.junit.jupiter.api.BeforeEach
    void gstOn() {
        org.springframework.test.util.ReflectionTestUtils.setField(issuer, "gstEnabled", true);
    }

    @Test
    void withGstSwitchedOffNoTaxIsAddedAndChargesCarryNone() {
        org.springframework.test.util.ReflectionTestUtils.setField(issuer, "gstEnabled", false);
        InvoiceDraftRequest draft = new InvoiceDraftRequest();

        issuer.applyDefaultGst(draft, Booking.builder().type(BookingType.FLIGHT).internationalTrip(false).build());

        assertThat(draft.getSupplyNature()).isEqualTo(SupplyNature.AIR_TICKET);
        assertThat(draft.getTaxes()).isNullOrEmpty();
        assertThat(issuer.cancellationChargesTotal(Booking.builder().type(BookingType.HOTEL).build(),
                new BigDecimal("1000"), new BigDecimal("500"))).isEqualByComparingTo("1500");
        org.mockito.Mockito.verifyNoInteractions(taxRateConfigRepository);
    }

    @Test
    void aFlightGetsTheAirTicketDefaultGst() {
        when(taxRateConfigRepository.findByTaxKindAndSupplyNatureAndIsDefaultTrueAndIsActiveTrue(TaxKind.GST, SupplyNature.AIR_TICKET))
                .thenReturn(Optional.of(TaxRateConfig.builder().id("T1").label("GST 18%").ratePercent(new BigDecimal("18")).build()));
        InvoiceDraftRequest draft = new InvoiceDraftRequest();

        issuer.applyDefaultGst(draft, Booking.builder().type(BookingType.FLIGHT).internationalTrip(false).build());

        assertThat(draft.getSupplyNature()).isEqualTo(SupplyNature.AIR_TICKET);
        assertThat(draft.getTaxes()).singleElement().satisfies(t -> {
            assertThat(t.getTaxRateConfigId()).isEqualTo("T1");
            assertThat(t.getRatePercent()).isEqualByComparingTo("18");
        });
    }

    /** An overseas tour package gets its GST and the TCS section 206C(1G) asks the agency to collect. */
    @Test
    void anOverseasPackageGetsItsDefaultTcsToo() {
        when(taxRateConfigRepository.findByTaxKindAndSupplyNatureAndIsDefaultTrueAndIsActiveTrue(TaxKind.GST, SupplyNature.OVERSEAS_PACKAGE))
                .thenReturn(Optional.of(TaxRateConfig.builder().id("G1").label("GST 5%").ratePercent(new BigDecimal("5")).build()));
        when(taxRateConfigRepository.findByTaxKindAndSupplyNatureAndIsDefaultTrueAndIsActiveTrue(TaxKind.TCS, SupplyNature.OVERSEAS_PACKAGE))
                .thenReturn(Optional.of(TaxRateConfig.builder().id("C1").label("TCS 5%").ratePercent(new BigDecimal("5")).build()));
        InvoiceDraftRequest draft = new InvoiceDraftRequest();

        issuer.applyDefaultGst(draft, Booking.builder().type(BookingType.PACKAGE).internationalTrip(true).build());

        assertThat(draft.getSupplyNature()).isEqualTo(SupplyNature.OVERSEAS_PACKAGE);
        assertThat(draft.getTaxes()).extracting(t -> t.getTaxRateConfigId()).containsExactly("G1", "C1");
    }

    @Test
    void noDefaultConfiguredMeansNoTax() {
        InvoiceDraftRequest draft = new InvoiceDraftRequest();
        issuer.applyDefaultGst(draft, Booking.builder().type(BookingType.TRANSFER).build());
        assertThat(draft.getSupplyNature()).isEqualTo(SupplyNature.OTHER);
        assertThat(draft.getTaxes()).isNull();
    }

    @Test
    void theCancellationInvoiceBillsWhatWasKeptWithTheSuppliersShareAsPassThrough() {
        when(invoiceDocumentService.createDraft(any())).thenReturn(InvoiceResponse.builder().id("CI1").build());
        when(invoiceDocumentService.issue("CI1")).thenReturn(InvoiceResponse.builder().id("CI1").grandTotal(new BigDecimal("1554")).build());

        InvoiceResponse issued = issuer.issueCancellationCharges(Booking.builder().id("B1").bookingNumber(42L).type(BookingType.FLIGHT).build(),
                new BigDecimal("1200"), new BigDecimal("300"));

        ArgumentCaptor<InvoiceDraftRequest> draft = ArgumentCaptor.forClass(InvoiceDraftRequest.class);
        verify(invoiceDocumentService).createDraft(draft.capture());
        assertThat(draft.getValue().getLines()).singleElement().satisfies(l -> {
            assertThat(l.getDescription()).isEqualTo("Cancellation charges - BK00000042");
            assertThat(l.getUnitPrice()).isEqualByComparingTo("1500");
        });
        assertThat(draft.getValue().getPassThroughAmount()).isEqualByComparingTo("1200");
        assertThat(issued.getGrandTotal()).isEqualByComparingTo("1554");
    }

    /** The cancel preview shows what the charges invoice will bill: GST on the agency's fee alone - the supplier's part passes through. */
    @Test
    void theCancellationChargesTotalCarriesTheSameGstTheInvoiceWill() {
        when(taxRateConfigRepository.findByTaxKindAndSupplyNatureAndIsDefaultTrueAndIsActiveTrue(TaxKind.GST, SupplyNature.AIR_TICKET))
                .thenReturn(Optional.of(TaxRateConfig.builder().id("T1").label("GST 18%").ratePercent(new BigDecimal("18")).build()));
        when(taxRateConfigRepository.findByTaxKindAndSupplyNatureAndIsDefaultTrueAndIsActiveTrue(TaxKind.GST, SupplyNature.HOTEL_ONLY))
                .thenReturn(Optional.of(TaxRateConfig.builder().id("T2").label("GST 18%").ratePercent(new BigDecimal("18")).build()));

        assertThat(issuer.cancellationChargesTotal(Booking.builder().type(BookingType.FLIGHT).internationalTrip(false).build(),
                new BigDecimal("1200"), new BigDecimal("300"))).isEqualByComparingTo("1554");
        assertThat(issuer.cancellationChargesTotal(Booking.builder().type(BookingType.HOTEL).build(),
                new BigDecimal("1200"), new BigDecimal("300"))).isEqualByComparingTo("1554");
        assertThat(issuer.cancellationChargesTotal(Booking.builder().type(BookingType.TRANSFER).build(),
                new BigDecimal("1200"), new BigDecimal("300"))).isEqualByComparingTo("1500");
    }
}
