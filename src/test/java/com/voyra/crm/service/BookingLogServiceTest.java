package com.voyra.crm.service;

import com.voyra.crm.dto.BookingLogRequest;
import com.voyra.crm.dto.BookingResponse;
import com.voyra.crm.enums.BookingDocumentType;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.LeadMemberRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.VendorRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingLogServiceTest {

    @Mock private BookingService bookingService;
    @Mock private BookingDocumentRepository bookingDocumentRepository;
    @Mock private LeadServiceRepository leadServiceRepository;
    @Mock private LeadMemberRepository leadMemberRepository;
    @Mock private VendorRepository vendorRepository;
    @Mock private FileStorageService fileStorageService;
    @InjectMocks private BookingLogService bookingLogService;

    private final MockMultipartFile voucher = new MockMultipartFile("file", "v.pdf", "application/pdf", new byte[]{1});

    @BeforeEach
    void signIn() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("A1", "agent", UserType.AGENT, "T1"), null, java.util.List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static BookingLogRequest adHoc() {
        BookingLogRequest r = new BookingLogRequest();
        r.setClientId("C1");
        r.setType(BookingType.HOTEL);
        r.setDestination("Goa");
        r.setReference(" HC-1 ");
        r.setNetCost(BigDecimal.valueOf(900));
        r.setSellingPrice(BigDecimal.valueOf(1000));
        return r;
    }

    @Test
    void storesTheVoucherThenCreatesTheConfirmedBookingWithIt() {
        when(fileStorageService.store(eq("T1"), eq("booking-documents"), anyString(), eq(voucher))).thenReturn("key-1");
        when(bookingService.logBooking(any(), anyString(), eq("key-1"), eq("v.pdf"), eq("application/pdf"),
                eq(BookingDocumentType.HOTEL_VOUCHER), eq("HC-1")))
                .thenReturn(BookingResponse.builder().id("B1").build());

        assertThat(bookingLogService.log(adHoc(), voucher).getId()).isEqualTo("B1");
        verify(fileStorageService, never()).delete(anyString());
    }

    @Test
    void deletesTheStoredVoucherWhenTheBookingIsRejected() {
        when(fileStorageService.store(any(), any(), any(), any())).thenReturn("key-2");
        when(bookingService.logBooking(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Client not found: C1"));

        assertThatThrownBy(() -> bookingLogService.log(adHoc(), voucher)).hasMessage("Client not found: C1");
        verify(fileStorageService).delete("key-2");
    }

    @Test
    void refusesABookingWithoutAVoucher() {
        MockMultipartFile empty = new MockMultipartFile("file", "v.pdf", "application/pdf", new byte[0]);
        assertThatThrownBy(() -> bookingLogService.log(adHoc(), empty)).hasMessage("Voucher file is required");
        verify(fileStorageService, never()).store(any(), any(), any(), any());
    }
}
