package com.voyra.crm.util;

import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.BookingLogRequest;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.enums.BookingDocumentType;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.InsuranceType;
import com.voyra.crm.enums.InvoiceServiceCategory;
import com.voyra.crm.enums.ServiceType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Travel insurance as its own service and booking type (client 27 Nov) through every helper that switches on a type. */
class InsuranceServiceTypeTest {

    private static final LocalDate START = LocalDate.of(2026, 12, 1);
    private static final LocalDate END = LocalDate.of(2026, 12, 15);

    private static LeadService cover() {
        return LeadService.builder().id("S1").leadId("L1").clientId("C1").type(ServiceType.INSURANCE)
                .insuranceType(InsuranceType.FAMILY).insuranceFromCountry("India").insuranceCountries("France, Italy")
                .insuranceStart(START).insuranceEnd(END).insurancePersons(3).leadDestination("Europe").build();
    }

    @Test
    void itIsItsOwnTypeBothWaysWithAnInsuranceVoucherAndTheMiscellaneousInvoiceSeries() {
        assertThat(ServiceBookingTypeMapper.forService(ServiceType.INSURANCE)).isEqualTo(BookingType.INSURANCE);
        assertThat(ServiceBookingTypeMapper.forBooking(BookingType.INSURANCE)).isEqualTo(ServiceType.INSURANCE);
        assertThat(BookingLogRequestMapper.voucherTypeFor(BookingType.INSURANCE)).isEqualTo(BookingDocumentType.INSURANCE);
        assertThat(InvoiceServiceCategory.forBooking(BookingType.INSURANCE, true)).isEqualTo(InvoiceServiceCategory.MISCELLANEOUS);
    }

    @Test
    void aLoggedBookingCarriesTheCoverItsCountriesAndDatesAndThePolicyNumberAsItsPnr() {
        BookingLogRequest log = new BookingLogRequest();
        log.setReference(" POL-9 ");
        log.setNetCost(new BigDecimal("900"));
        log.setSellingPrice(new BigDecimal("1000"));

        BookingCreateRequest req = BookingLogRequestMapper.toCreateRequest(log, cover(), List.of(), "Acko");

        assertThat(req.getType()).isEqualTo(BookingType.INSURANCE);
        assertThat(req.getDestination()).isEqualTo("France, Italy");
        assertThat(req.getPnr()).isEqualTo("POL-9");
        assertThat(req.getJourneyDate()).isEqualTo(START);
        assertThat(req.getReturnDate()).isEqualTo(END);
        assertThat(req.getInsuranceType()).isEqualTo(InsuranceType.FAMILY);
        assertThat(req.getInsuranceFromCountry()).isEqualTo("India");
        assertThat(req.getInsurancePersons()).isEqualTo(3);

        LeadService noCountries = cover();
        noCountries.setInsuranceCountries(null);
        assertThat(BookingLogRequestMapper.toCreateRequest(log, noCountries, List.of(), "Acko").getDestination()).isEqualTo("Europe");
    }

    @Test
    void itsDatesAreTheCoverAndAnOpenEndedCoverEndsTheDayItStarts() {
        LeadService s = cover();
        ServiceDateRangeDeriver.derive(s);
        assertThat(s.getDateFrom()).isEqualTo(START);
        assertThat(s.getDateTo()).isEqualTo(END);
        s.setInsuranceEnd(null);
        ServiceDateRangeDeriver.derive(s);
        assertThat(s.getDateTo()).isEqualTo(START);
    }

    @Test
    void twoAreToldApartByTheirCountries() {
        LeadService europe = cover();
        LeadService asia = cover();
        asia.setInsuranceCountries("Japan");
        ServiceLabelDeriver.deriveAll(new ArrayList<>(List.of(europe, asia)));
        assertThat(europe.getLabel()).isEqualTo("Insurance — France, Italy");
        assertThat(asia.getLabel()).isEqualTo("Insurance — Japan");
        LeadService only = cover();
        ServiceLabelDeriver.deriveAll(new ArrayList<>(List.of(only)));
        assertThat(only.getLabel()).isEqualTo("Insurance");
    }

    @Test
    void aCoverCannotEndBeforeItStarts() {
        assertThatThrownBy(() -> TripRules.journey(BookingType.INSURANCE, END, START)).hasMessage("The end date can't be before the start date");
        TripRules.journey(BookingType.INSURANCE, START, END);
    }

    @Test
    void theInvoiceLineSaysWhatTheCoverIs() {
        Booking full = Booking.builder().type(BookingType.INSURANCE).insuranceType(InsuranceType.SENIOR_CITIZEN).insurancePersons(1)
                .destination("France, Italy").journeyDate(START).returnDate(END).build();
        assertThat(BookingInvoiceLineBuilder.insuranceText(full)).isEqualTo("Travel insurance (Senior citizen, 1 person): France, Italy, "
                + BookingInvoiceLineBuilder.fmt(START) + " to " + BookingInvoiceLineBuilder.fmt(END));
        Booking family = Booking.builder().type(BookingType.INSURANCE).insurancePersons(4).destination(" ").build();
        assertThat(BookingInvoiceLineBuilder.insuranceText(family)).isEqualTo("Travel insurance (4 persons)");
        assertThat(BookingInvoiceLineBuilder.insuranceText(Booking.builder().type(BookingType.INSURANCE).build())).isEqualTo("Travel insurance");
        assertThat(BookingInvoiceLineBuilder.build(full.toBuilder().sellingPrice(new BigDecimal("1000")).build(),
                InvoiceServiceCategory.MISCELLANEOUS, List.of(), java.util.Map.of()).get(0).getDescription()).startsWith("Travel insurance (Senior citizen");
    }
}
