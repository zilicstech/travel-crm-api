package com.voyra.crm.service;

import com.voyra.crm.entity.Booking;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.ClientRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantScopedReadServiceTest {

    @Mock BookingRepository bookingRepository;
    @Mock ClientRepository clientRepository;
    @InjectMocks TenantScopedReadService service;

    @Test
    void revenueSumsEveryBookingButCancelledOnesCountingAMissingPriceAsZero() {
        when(bookingRepository.findAll()).thenReturn(List.of(
                Booking.builder().bookingStatus(BookingStatus.CONFIRMED).sellingPrice(new BigDecimal("1000.50")).build(),
                Booking.builder().bookingStatus(BookingStatus.COMPLETED).sellingPrice(null).build(),
                Booking.builder().bookingStatus(BookingStatus.CANCELLED).sellingPrice(new BigDecimal("9999")).build(),
                Booking.builder().bookingStatus(BookingStatus.PENDING).sellingPrice(new BigDecimal("200")).build()));

        assertThat(service.sumTotalBookingRevenue()).isEqualByComparingTo("1200.50");
    }

    @Test
    void countsActiveClientsAndTotalBookingsInTheTenantSchema() {
        when(clientRepository.countByIsActiveTrue()).thenReturn(14L);
        when(bookingRepository.count()).thenReturn(9L);

        assertThat(service.countClients()).isEqualTo(14L);
        assertThat(service.countBookings()).isEqualTo(9L);
    }
}
