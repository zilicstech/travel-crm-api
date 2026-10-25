package com.voyra.crm.service;

import com.voyra.crm.dto.StatementImportResponse;
import com.voyra.crm.dto.StatementLineResponse;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformStatementImport;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.StatementLineStatus;
import com.voyra.crm.enums.StatementLineType;
import com.voyra.crm.enums.VoyraEarnedSource;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.repository.PlatformStatementImportRepository;
import com.voyra.crm.repository.PlatformStatementLineRepository;
import com.voyra.crm.service.booking.PlatformBookingEventRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TripjackStatementServiceTest {

    private static final String HEADER = "booking_id,pnr,type,amount,commission,date\n";

    @Mock
    private PlatformBookingRepository bookingRepository;
    @Mock
    private PlatformStatementImportRepository importRepository;
    @Mock
    private PlatformStatementLineRepository lineRepository;
    @Mock
    private PlatformAuditService auditService;
    @Mock
    private PlatformBookingEventRecorder events;
    @Spy
    private TripjackStatementConfig config = new TripjackStatementConfig();
    @InjectMocks
    private TripjackStatementService service;

    @BeforeEach
    void saves() {
        given(importRepository.save(any())).willAnswer(i -> i.getArgument(0));
        given(lineRepository.saveAll(any())).willAnswer(i -> i.getArgument(0));
        given(bookingRepository.findBySupplierBookingId(anyString())).willReturn(List.of());
        given(bookingRepository.findByPnr(anyString())).willReturn(List.of());
    }

    private static MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "tj-oct.csv", "text/csv", body.getBytes(StandardCharsets.UTF_8));
    }

    /** gross 10000, displayed 9500, estimate 150, commission 100: formula net -450, plus a -20 fare variance. */
    private static PlatformBooking ticketed() {
        return PlatformBooking.builder().id("B1").tenantId("T1").status(PlatformBookingStatus.TICKETED)
                .supplierBookingId("TJ-1").pnr("ABC123").gross(new BigDecimal("10000.00")).displayed(new BigDecimal("9500.00"))
                .voyraEarned(new BigDecimal("150.00")).voyraEarnedSource(VoyraEarnedSource.ESTIMATE)
                .agencyCommission(new BigDecimal("100.00")).tds(BigDecimal.ZERO).voyraNet(new BigDecimal("-470.00"))
                .ticketedAt(Instant.parse("2026-10-05T06:00:00Z")).build();
    }

    /** Cancelled with a 9000 supplier refund passed on in full: -450 - 150 earned reversed + 100 commission recovered. */
    private static PlatformBooking cancelled() {
        return ticketed().toBuilder().id("B2").supplierBookingId("TJ-2").pnr("XYZ789").status(PlatformBookingStatus.CANCELLED)
                .voyraEarnedReversal(new BigDecimal("150.00")).voyraNet(new BigDecimal("-500.00")).build();
    }

    private void found(PlatformBooking b) {
        given(bookingRepository.findBySupplierBookingId(b.getSupplierBookingId())).willReturn(List.of(b));
        given(bookingRepository.findByPnr(b.getPnr())).willReturn(List.of(b));
        given(bookingRepository.findByIdForUpdate(b.getId())).willReturn(Optional.of(b));
    }

    private static StatementLineResponse line(StatementImportResponse r, int n) {
        return r.getLines().stream().filter(l -> l.getLineNo() == n).findFirst().orElseThrow();
    }

    @Test
    void saleLineReplacesTheEstimateAndRederivesNetKeepingTheVariance() {
        PlatformBooking b = ticketed();
        found(b);
        StatementImportResponse r = service.importStatement(csv(HEADER + "TJ-1,ABC123,SALE,10000.00,180.00,2026-10-05\n"), "A1", "Admin");

        assertThat(b.getVoyraEarned()).isEqualByComparingTo("180.00");
        assertThat(b.getVoyraEarnedActual()).isEqualByComparingTo("180.00");
        assertThat(b.getVoyraEarnedSource()).isEqualTo(VoyraEarnedSource.STATEMENT);
        // 180 - 500 discount - 100 commission = -420, and the -20 variance carries over
        assertThat(b.getVoyraNet()).isEqualByComparingTo("-440.00");
        assertThat(line(r, 2).getStatus()).isEqualTo(StatementLineStatus.APPLIED);
        assertThat(line(r, 2).getPlatformBookingId()).isEqualTo("B1");
        assertThat(r.getAppliedLines()).isEqualTo(1);
        verify(bookingRepository).save(b);
        verify(auditService).record(eq(PlatformAuditAction.STATEMENT_IMPORT), eq("STATEMENT_IMPORT"), anyString(), any());
    }

    @Test
    void sameFileTwiceIsRefusedAndReconcilesNothing() {
        given(importRepository.existsByFileSha256(anyString())).willReturn(true);
        assertThatThrownBy(() -> service.importStatement(csv(HEADER + "TJ-1,ABC123,SALE,10000,180,2026-10-05\n"), "A1", "Admin"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Already imported");
        verify(bookingRepository, never()).findByIdForUpdate(anyString());
        verify(bookingRepository, never()).save(any());
        verify(importRepository, never()).save(any());
    }

    @Test
    void theFileHashIsSha256OfTheBytes() {
        service.importStatement(csv(HEADER + "TJ-9,,SALE,1,1,2026-10-05\n"), "A1", "Admin");
        // sha256("booking_id,pnr,type,amount,commission,date\nTJ-9,,SALE,1,1,2026-10-05\n")
        verify(importRepository).existsByFileSha256(TripjackStatementService.sha256(
                (HEADER + "TJ-9,,SALE,1,1,2026-10-05\n").getBytes(StandardCharsets.UTF_8)));
        assertThat(TripjackStatementService.sha256("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void aLineAlreadyAppliedFromAnEarlierFileIsADuplicateAndChangesNothing() {
        PlatformBooking b = ticketed();
        found(b);
        given(lineRepository.existsByPlatformBookingIdAndTypeAndStatus("B1", StatementLineType.SALE, StatementLineStatus.APPLIED))
                .willReturn(true);
        StatementImportResponse r = service.importStatement(csv(HEADER + "TJ-1,ABC123,SALE,10000,999,05-10-2026\n"), "A1", "Admin");

        assertThat(line(r, 2).getStatus()).isEqualTo(StatementLineStatus.DUPLICATE);
        assertThat(b.getVoyraEarned()).isEqualByComparingTo("150.00");
        assertThat(b.getVoyraNet()).isEqualByComparingTo("-470.00");
        assertThat(r.getDuplicateLines()).isEqualTo(1);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void theSameLineTwiceInOneFileIsAppliedOnce() {
        PlatformBooking b = ticketed();
        found(b);
        StatementImportResponse r = service.importStatement(csv(HEADER
                + "TJ-1,ABC123,SALE,10000,180,2026-10-05\n"
                + ",ABC123,SALE,10000,180,2026-10-05\n"), "A1", "Admin");

        assertThat(line(r, 2).getStatus()).isEqualTo(StatementLineStatus.APPLIED);
        assertThat(line(r, 3).getStatus()).isEqualTo(StatementLineStatus.DUPLICATE);
        assertThat(b.getVoyraNet()).isEqualByComparingTo("-440.00");
    }

    @Test
    void matchesBySupplierBookingIdFirstThenPnr() {
        PlatformBooking byId = ticketed();
        PlatformBooking byPnr = ticketed().toBuilder().id("B3").supplierBookingId("TJ-3").pnr("PNR333").build();
        given(bookingRepository.findBySupplierBookingId("TJ-1")).willReturn(List.of(byId));
        given(bookingRepository.findByPnr("PNR333")).willReturn(List.of(byPnr));
        given(bookingRepository.findByIdForUpdate("B1")).willReturn(Optional.of(byId));
        given(bookingRepository.findByIdForUpdate("B3")).willReturn(Optional.of(byPnr));

        StatementImportResponse r = service.importStatement(csv(HEADER
                + "TJ-1,PNR333,SALE,10000,180,2026-10-05\n"    // id wins over a PNR pointing elsewhere
                + "TJ-UNKNOWN,PNR333,SALE,10000,160,2026-10-05\n"), "A1", "Admin");

        assertThat(line(r, 2).getPlatformBookingId()).isEqualTo("B1");
        assertThat(line(r, 3).getPlatformBookingId()).isEqualTo("B3");
        assertThat(byPnr.getVoyraEarned()).isEqualByComparingTo("160.00");
    }

    @Test
    void unmatchedLinesAreListedWithAReason() {
        PlatformBooking open = ticketed().toBuilder().id("B4").supplierBookingId("TJ-4").pnr("OPEN44")
                .status(PlatformBookingStatus.BOOKING).ticketedAt(null).build();
        found(open);
        PlatformBooking live = ticketed();
        found(live);
        PlatformBooking twin = ticketed().toBuilder().id("B5").supplierBookingId("TJ-5").pnr("DUP555").build();
        given(bookingRepository.findByPnr("DUP555")).willReturn(List.of(twin, twin.toBuilder().id("B6").build()));

        StatementImportResponse r = service.importStatement(csv(HEADER
                + "TJ-NOPE,NOPE00,SALE,100,1,2026-10-05\n"
                + "TJ-4,OPEN44,SALE,100,1,2026-10-05\n"
                + "TJ-1,ABC123,REFUND,-10000,-180,2026-10-09\n"
                + ",DUP555,SALE,100,1,2026-10-05\n"
                + ",,SALE,100,1,2026-10-05\n"), "A1", "Admin");

        assertThat(r.getUnmatchedLines()).isEqualTo(5);
        assertThat(line(r, 2).getReason()).isEqualTo("No booking with this booking id or PNR");
        assertThat(line(r, 3).getReason()).isEqualTo("Booking was never ticketed (BOOKING)");
        assertThat(line(r, 4).getReason()).isEqualTo("Refund for a booking that is not cancelled (TICKETED)");
        assertThat(line(r, 5).getReason()).isEqualTo("PNR matches 2 bookings");
        assertThat(line(r, 6).getReason()).isEqualTo("No booking id or PNR");
        assertThat(r.getLines()).allMatch(l -> l.getStatus() == StatementLineStatus.UNMATCHED);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void refundLineSetsTheActualClawbackAsTheReversal() {
        PlatformBooking b = cancelled();
        found(b);
        StatementImportResponse r = service.importStatement(csv(HEADER + "TJ-2,XYZ789,REFUND,-9000,-180,2026-10-09\n"), "A1", "Admin");

        assertThat(line(r, 2).getStatus()).isEqualTo(StatementLineStatus.APPLIED);
        assertThat(b.getVoyraEarnedReversal()).isEqualByComparingTo("180.00");
        assertThat(b.getVoyraEarned()).isEqualByComparingTo("150.00"); // the sale is not on this statement yet
        assertThat(b.getVoyraNet()).isEqualByComparingTo("-530.00");
    }

    @Test
    void saleOnACancelledBookingWithoutAStatementRefundMovesTheReversalWithIt() {
        PlatformBooking b = cancelled();
        found(b);
        service.importStatement(csv(HEADER + "TJ-2,XYZ789,SALE,10000,180,2026-10-05\n"), "A1", "Admin");

        assertThat(b.getVoyraEarned()).isEqualByComparingTo("180.00");
        assertThat(b.getVoyraEarnedReversal()).isEqualByComparingTo("180.00");
        assertThat(b.getVoyraNet()).isEqualByComparingTo("-500.00"); // a cancelled fare earns nothing either way
    }

    @Test
    void saleAndRefundInOneFileLeaveTheActualsOnACancelledBooking() {
        PlatformBooking b = cancelled();
        found(b);
        service.importStatement(csv(HEADER
                + "TJ-2,XYZ789,REFUND,-9000,-170,2026-10-09\n"
                + "TJ-2,XYZ789,SALE,10000,180,2026-10-05\n"), "A1", "Admin");

        assertThat(b.getVoyraEarned()).isEqualByComparingTo("180.00");
        assertThat(b.getVoyraEarnedReversal()).isEqualByComparingTo("170.00");
        // -500 + (180 - 150) earned - (170 - 150) reversal
        assertThat(b.getVoyraNet()).isEqualByComparingTo("-490.00");
    }

    @Test
    void columnsAreMappedByHeaderNameInAnyOrderAndCase() {
        PlatformBooking b = ticketed();
        found(b);
        StatementImportResponse r = service.importStatement(csv("﻿Date,Type,Commission,Amount,PNR,Booking_ID\n"
                + "05-10-2026,sale,\"1,180.50\",\"10,000.00\",ABC123,TJ-1\n"), "A1", "Admin");

        assertThat(b.getVoyraEarned()).isEqualByComparingTo("1180.50");
        assertThat(line(r, 2).getDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(line(r, 2).getType()).isEqualTo(StatementLineType.SALE);
    }

    @Test
    void aRenamedColumnIsAConfigChange() {
        config.getColumns().setBookingId("reference");
        PlatformBooking b = ticketed();
        found(b);
        service.importStatement(csv("reference,pnr,type,amount,commission,date\nTJ-1,,SALE,10000,180,2026-10-05\n"), "A1", "Admin");
        assertThat(b.getVoyraEarned()).isEqualByComparingTo("180.00");
    }

    @Test
    void aMissingColumnIsRefusedNamingIt() {
        assertThatThrownBy(() -> service.importStatement(csv("booking_id,pnr,type,amount,date\nTJ-1,,SALE,1,2026-10-05\n"), "A1", "Admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("commission");
        verifyNoInteractions(auditService);
    }

    @Test
    void badValuesRefuseTheWholeFileWithTheLineNumber() {
        found(ticketed());
        assertThatThrownBy(() -> service.importStatement(csv(HEADER
                + "TJ-1,ABC123,SALE,10000,180,2026-10-05\n"
                + "TJ-1,ABC123,SALE,abc,180,2026-10-05\n"), "A1", "Admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Line 3").hasMessageContaining("amount");
        assertThatThrownBy(() -> service.importStatement(csv(HEADER + "TJ-1,ABC123,SALE,1,1,10/05/2026\n"), "A1", "Admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Line 2").hasMessageContaining("date");
        assertThatThrownBy(() -> service.importStatement(csv(HEADER + "TJ-1,ABC123,VOID,1,1,2026-10-05\n"), "A1", "Admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SALE or REFUND");
        assertThatThrownBy(() -> service.importStatement(csv(HEADER + "TJ-1,ABC123,SALE,1,-5,2026-10-05\n"), "A1", "Admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("commission");
        verify(bookingRepository, never()).save(any());
        verify(importRepository, never()).save(any());
    }

    @Test
    void onlyCsvUpToFiveMbWithAHeaderAndRowsIsAccepted() {
        assertThatThrownBy(() -> service.importStatement(
                new MockMultipartFile("file", "tj.xlsx", "application/octet-stream", "PK\u0003\u0004".getBytes()), "A1", "Admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CSV");
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        java.util.Arrays.fill(big, (byte) 'a');
        assertThatThrownBy(() -> service.importStatement(new MockMultipartFile("file", "tj.csv", "text/csv", big), "A1", "Admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("5 MB");
        assertThatThrownBy(() -> service.importStatement(csv(""), "A1", "Admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("header");
        assertThatThrownBy(() -> service.importStatement(csv(HEADER), "A1", "Admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no lines");
    }

    @Test
    void theImportRecordsCountsAndWhoRanIt() {
        found(ticketed());
        service.importStatement(csv(HEADER + "TJ-1,ABC123,SALE,10000,180,2026-10-05\nTJ-NOPE,,SALE,1,1,2026-10-05\n"), "A1", "Admin");
        org.mockito.ArgumentCaptor<PlatformStatementImport> saved = org.mockito.ArgumentCaptor.forClass(PlatformStatementImport.class);
        verify(importRepository).save(saved.capture());
        assertThat(saved.getValue().getFileName()).isEqualTo("tj-oct.csv");
        assertThat(saved.getValue().getTotalLines()).isEqualTo(2);
        assertThat(saved.getValue().getAppliedLines()).isEqualTo(1);
        assertThat(saved.getValue().getUnmatchedLines()).isEqualTo(1);
        assertThat(saved.getValue().getImportedBy()).isEqualTo("A1");
        assertThat(saved.getValue().getFileSha256()).hasSize(64);
    }
}
