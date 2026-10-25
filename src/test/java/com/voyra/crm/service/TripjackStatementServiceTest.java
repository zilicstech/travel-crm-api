package com.voyra.crm.service;

import com.voyra.crm.dto.StatementImportResponse;
import com.voyra.crm.dto.StatementLineResponse;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformStatementImport;
import com.voyra.crm.entity.PlatformStatementLine;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.StatementLineStatus;
import com.voyra.crm.enums.StatementLineType;
import com.voyra.crm.enums.SupplierEnvironment;
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
import static org.mockito.Mockito.times;
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
    @Mock
    private NotificationService notificationService;
    @Spy
    private TripjackStatementConfig config = new TripjackStatementConfig();
    @InjectMocks
    private TripjackStatementService service;

    @BeforeEach
    void saves() {
        given(importRepository.save(any())).willAnswer(i -> i.getArgument(0));
        given(lineRepository.saveAll(any())).willAnswer(i -> i.getArgument(0));
        given(bookingRepository.findIdsBySupplierBookingId(anyString(), any())).willReturn(List.of());
        given(bookingRepository.findIdsByPnr(anyString(), any())).willReturn(List.of());
    }

    private static MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "tj-oct.csv", "text/csv", body.getBytes(StandardCharsets.UTF_8));
    }

    /** gross 10000, displayed 9500, estimate 150, commission 100: formula net -450, plus a -20 fare variance. */
    private static PlatformBooking ticketed() {
        return PlatformBooking.builder().id("B1").tenantId("T1").status(PlatformBookingStatus.TICKETED)
                .environment(SupplierEnvironment.PROD).supplierBookingId("TJ-1").pnr("ABC123").gross(new BigDecimal("10000.00")).displayed(new BigDecimal("9500.00"))
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
        given(bookingRepository.findIdsBySupplierBookingId(b.getSupplierBookingId(), b.getEnvironment())).willReturn(List.of(b.getId()));
        given(bookingRepository.findIdsByPnr(b.getPnr(), b.getEnvironment())).willReturn(List.of(b.getId()));
        given(bookingRepository.findByIdForUpdate(b.getId())).willReturn(Optional.of(b));
    }

    private StatementImportResponse run(String body) {
        return service.importStatement(csv(body), SupplierEnvironment.PROD, "A1", "Admin");
    }

    private static StatementLineResponse line(StatementImportResponse r, int n) {
        return r.getLines().stream().filter(l -> l.getLineNo() == n).findFirst().orElseThrow();
    }

    @Test
    void saleLineSetsTheActualKeepsTheEstimateAndRederivesNetKeepingTheVariance() {
        PlatformBooking b = ticketed();
        found(b);
        StatementImportResponse r = run(HEADER + "TJ-1,ABC123,SALE,10000.00,180.00,2026-10-05\n");

        assertThat(b.getVoyraEarned()).isEqualByComparingTo("150.00"); // the estimate stays as stamped
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

    /**
     * Review Focus 2, after the final review: the same file may be uploaded again (an operator fixes an unmatched cause and
     * re-imports), but per-line idempotency still reconciles each line once - the second upload shows the applied lines as
     * DUPLICATE and changes nothing, while a line unmatched the first time can now apply.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theSameFileTwiceAppliesEachLineOnce() {
        List<PlatformStatementLine> stored = new java.util.ArrayList<>();
        given(lineRepository.saveAll(any())).willAnswer(i -> {
            ((Iterable<PlatformStatementLine>) i.getArgument(0)).forEach(stored::add);
            return i.getArgument(0);
        });
        given(lineRepository.existsByPlatformBookingIdAndTypeAndStatus(anyString(), any(), any())).willAnswer(i -> stored.stream()
                .anyMatch(l -> i.getArgument(0).equals(l.getPlatformBookingId()) && l.getType() == i.getArgument(1)
                        && l.getStatus() == i.getArgument(2)));
        PlatformBooking b = ticketed();
        found(b);
        String file = HEADER + "TJ-1,ABC123,SALE,10000,180,2026-10-05\n" + "TJ-2,XYZ789,SALE,10000,170,2026-10-05\n";

        StatementImportResponse first = run(file);
        assertThat(line(first, 2).getStatus()).isEqualTo(StatementLineStatus.APPLIED);
        assertThat(line(first, 3).getStatus()).isEqualTo(StatementLineStatus.UNMATCHED);

        PlatformBooking other = cancelled().toBuilder().status(PlatformBookingStatus.TICKETED).build(); // the unmatched cause, fixed
        found(other);
        StatementImportResponse second = run(file);

        assertThat(line(second, 2).getStatus()).isEqualTo(StatementLineStatus.DUPLICATE);
        assertThat(line(second, 3).getStatus()).isEqualTo(StatementLineStatus.APPLIED);
        assertThat(b.getVoyraNet()).isEqualByComparingTo("-440.00"); // applied once, not twice
        verify(bookingRepository, times(1)).save(b);
        verify(importRepository, times(2)).save(any());
    }

    @Test
    void theFileHashIsSha256OfTheBytesAndRecordedOnTheImport() {
        run(HEADER + "TJ-9,,SALE,1,1,2026-10-05\n");
        org.mockito.ArgumentCaptor<PlatformStatementImport> imp = org.mockito.ArgumentCaptor.forClass(PlatformStatementImport.class);
        verify(importRepository).save(imp.capture());
        assertThat(imp.getValue().getFileSha256()).isEqualTo(TripjackStatementService.sha256(
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
        StatementImportResponse r = run(HEADER + "TJ-1,ABC123,SALE,10000,999,05-10-2026\n");

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
        StatementImportResponse r = run(HEADER
                + "TJ-1,ABC123,SALE,10000,180,2026-10-05\n"
                + ",ABC123,SALE,10000,180,2026-10-05\n");

        assertThat(line(r, 2).getStatus()).isEqualTo(StatementLineStatus.APPLIED);
        assertThat(line(r, 3).getStatus()).isEqualTo(StatementLineStatus.DUPLICATE);
        assertThat(b.getVoyraNet()).isEqualByComparingTo("-440.00");
    }

    @Test
    void matchesBySupplierBookingIdAndFallsBackToPnrOnlyWhenTheIdIsBlank() {
        PlatformBooking byId = ticketed();
        PlatformBooking byPnr = ticketed().toBuilder().id("B3").supplierBookingId("TJ-3").pnr("PNR333").build();
        given(bookingRepository.findIdsBySupplierBookingId("TJ-1", SupplierEnvironment.PROD)).willReturn(List.of("B1"));
        given(bookingRepository.findIdsByPnr("PNR333", SupplierEnvironment.PROD)).willReturn(List.of("B3"));
        given(bookingRepository.findByIdForUpdate("B1")).willReturn(Optional.of(byId));
        given(bookingRepository.findByIdForUpdate("B3")).willReturn(Optional.of(byPnr));

        StatementImportResponse r = run(HEADER
                + "TJ-1,PNR333,SALE,10000,180,2026-10-05\n"        // id wins over a PNR pointing elsewhere
                + "TJ-UNKNOWN,PNR333,SALE,10000,160,2026-10-05\n"  // unknown id: a recycled PNR must not match
                + ",PNR333,SALE,10000,160,2026-10-05\n");         // no id: PNR it is

        assertThat(line(r, 2).getPlatformBookingId()).isEqualTo("B1");
        assertThat(line(r, 3).getStatus()).isEqualTo(StatementLineStatus.UNMATCHED);
        assertThat(line(r, 3).getReason()).isEqualTo("No booking with this booking id");
        assertThat(line(r, 3).getPlatformBookingId()).isNull();
        assertThat(line(r, 4).getPlatformBookingId()).isEqualTo("B3");
        assertThat(byPnr.getVoyraEarnedActual()).isEqualByComparingTo("160.00");
    }

    @Test
    void onlyBookingsOfTheImportsEnvironmentMatch() {
        PlatformBooking uat = ticketed().toBuilder().environment(SupplierEnvironment.UAT).build();
        found(uat); // findable in UAT only

        StatementImportResponse r = run(HEADER + "TJ-1,ABC123,SALE,10000,180,2026-10-05\n");

        assertThat(line(r, 2).getStatus()).isEqualTo(StatementLineStatus.UNMATCHED);
        verify(bookingRepository).findIdsBySupplierBookingId("TJ-1", SupplierEnvironment.PROD);
        verify(bookingRepository, never()).findByIdForUpdate(anyString());

        StatementImportResponse asUat = service.importStatement(csv(HEADER + "TJ-1,ABC123,SALE,10000,180,2026-10-06\n"),
                SupplierEnvironment.UAT, "A1", "Admin");
        assertThat(line(asUat, 2).getStatus()).isEqualTo(StatementLineStatus.APPLIED);
    }

    @Test
    void decisionsAndWritesUseTheLockedRowNotAnEarlierRead() {
        // matching yields an id only; the row as locked is CANCELLED by a concurrent cancel that committed first
        given(bookingRepository.findIdsBySupplierBookingId("TJ-2", SupplierEnvironment.PROD)).willReturn(List.of("B2"));
        PlatformBooking locked = cancelled();
        given(bookingRepository.findByIdForUpdate("B2")).willReturn(Optional.of(locked));

        StatementImportResponse r = run(HEADER + "TJ-2,XYZ789,REFUND,-9000,-180,2026-10-09\n");

        assertThat(line(r, 2).getStatus()).isEqualTo(StatementLineStatus.APPLIED);
        assertThat(locked.getVoyraNet()).isEqualByComparingTo("-530.00");
        verify(bookingRepository).save(org.mockito.ArgumentMatchers.same(locked));
    }

    @Test
    void oneAlertListsTheBookingsTheImportPushedFurtherBelowZero() {
        PlatformBooking worse = ticketed();                                     // -470 -> -490 (earned 150 -> 130)
        PlatformBooking better = ticketed().toBuilder().id("B7").supplierBookingId("TJ-7").pnr("PNR777").build(); // -> -440
        PlatformBooking positive = ticketed().toBuilder().id("B8").supplierBookingId("TJ-8").pnr("PNR888")
                .voyraNet(new BigDecimal("700.00")).build();                    // 700 -> 690: worse but not below zero
        found(worse);
        found(better);
        found(positive);

        run(HEADER
                + "TJ-1,ABC123,SALE,10000,130,2026-10-05\n"
                + "TJ-7,PNR777,SALE,10000,180,2026-10-05\n"
                + "TJ-8,PNR888,SALE,10000,140,2026-10-05\n");

        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notificationService, org.mockito.Mockito.times(1)).platformAlert(anyString(), body.capture());
        assertThat(body.getValue()).contains("B1").contains("-490.00").doesNotContain("B7").doesNotContain("B8");
    }

    @Test
    void noAlertWhenNoBookingGotWorseBelowZero() {
        found(ticketed());
        run(HEADER + "TJ-1,ABC123,SALE,10000,180,2026-10-05\n");
        verifyNoInteractions(notificationService);
    }

    @Test
    void quotedFieldsMayHoldNewlinesAndBareCarriageReturnsEndLines() {
        PlatformBooking b = ticketed();
        found(b);
        PlatformBooking other = ticketed().toBuilder().id("B9").supplierBookingId("TJ-9").pnr("PNR999").build();
        found(other);

        StatementImportResponse r = run("booking_id,pnr,type,amount,commission,date,remarks\r"
                + "TJ-1,ABC123,SALE,10000,180,2026-10-05,\"two\nline, \"\"quoted\"\" note\"\r"
                + "TJ-9,PNR999,SALE,10000,170,2026-10-05,plain\r\n");

        assertThat(r.getTotalLines()).isEqualTo(2);
        assertThat(b.getVoyraEarnedActual()).isEqualByComparingTo("180.00");
        assertThat(other.getVoyraEarnedActual()).isEqualByComparingTo("170.00");
        assertThat(line(r, 2).getStatus()).isEqualTo(StatementLineStatus.APPLIED);
        assertThat(line(r, 4).getStatus()).isEqualTo(StatementLineStatus.APPLIED); // the record after a 2-line field starts on line 4
    }

    @Test
    void unmatchedLinesAreListedWithAReason() {
        PlatformBooking open = ticketed().toBuilder().id("B4").supplierBookingId("TJ-4").pnr("OPEN44")
                .status(PlatformBookingStatus.BOOKING).ticketedAt(null).build();
        found(open);
        PlatformBooking live = ticketed();
        found(live);
        PlatformBooking twin = ticketed().toBuilder().id("B5").supplierBookingId("TJ-5").pnr("DUP555").build();
        given(bookingRepository.findIdsByPnr("DUP555", SupplierEnvironment.PROD)).willReturn(List.of("B5", "B6"));

        StatementImportResponse r = run(HEADER
                + "TJ-NOPE,NOPE00,SALE,100,1,2026-10-05\n"
                + "TJ-4,OPEN44,SALE,100,1,2026-10-05\n"
                + "TJ-1,ABC123,REFUND,-10000,-180,2026-10-09\n"
                + ",DUP555,SALE,100,1,2026-10-05\n"
                + ",,SALE,100,1,2026-10-05\n");

        assertThat(r.getUnmatchedLines()).isEqualTo(5);
        assertThat(line(r, 2).getReason()).isEqualTo("No booking with this booking id");
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
        StatementImportResponse r = run(HEADER + "TJ-2,XYZ789,REFUND,-9000,-180,2026-10-09\n");

        assertThat(line(r, 2).getStatus()).isEqualTo(StatementLineStatus.APPLIED);
        assertThat(b.getVoyraEarnedReversal()).isEqualByComparingTo("180.00");
        assertThat(b.getVoyraEarnedActual()).isNull(); // the sale is not on this statement yet
        assertThat(b.getVoyraNet()).isEqualByComparingTo("-530.00");
    }

    @Test
    void saleOnACancelledBookingWithoutAStatementRefundMovesTheReversalWithIt() {
        PlatformBooking b = cancelled();
        found(b);
        run(HEADER + "TJ-2,XYZ789,SALE,10000,180,2026-10-05\n");

        assertThat(b.getVoyraEarnedActual()).isEqualByComparingTo("180.00");
        assertThat(b.getVoyraEarned()).isEqualByComparingTo("150.00");
        assertThat(b.getVoyraEarnedReversal()).isEqualByComparingTo("180.00");
        assertThat(b.getVoyraNet()).isEqualByComparingTo("-500.00"); // a cancelled fare earns nothing either way
    }

    @Test
    void saleAndRefundInOneFileLeaveTheActualsOnACancelledBooking() {
        PlatformBooking b = cancelled();
        found(b);
        run(HEADER
                + "TJ-2,XYZ789,REFUND,-9000,-170,2026-10-09\n"
                + "TJ-2,XYZ789,SALE,10000,180,2026-10-05\n");

        assertThat(b.getVoyraEarnedActual()).isEqualByComparingTo("180.00");
        assertThat(b.getVoyraEarnedReversal()).isEqualByComparingTo("170.00");
        // -500 + (180 - 150) earned - (170 - 150) reversal
        assertThat(b.getVoyraNet()).isEqualByComparingTo("-490.00");
    }

    @Test
    void columnsAreMappedByHeaderNameInAnyOrderAndCase() {
        PlatformBooking b = ticketed();
        found(b);
        StatementImportResponse r = run("﻿Date,Type,Commission,Amount,PNR,Booking_ID\n"
                + "05-10-2026,sale,\"1,180.50\",\"10,000.00\",ABC123,TJ-1\n");

        assertThat(b.getVoyraEarnedActual()).isEqualByComparingTo("1180.50");
        assertThat(line(r, 2).getDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(line(r, 2).getType()).isEqualTo(StatementLineType.SALE);
    }

    @Test
    void aRenamedColumnIsAConfigChange() {
        config.getColumns().setBookingId("reference");
        PlatformBooking b = ticketed();
        found(b);
        run("reference,pnr,type,amount,commission,date\nTJ-1,,SALE,10000,180,2026-10-05\n");
        assertThat(b.getVoyraEarnedActual()).isEqualByComparingTo("180.00");
    }

    @Test
    void aMissingColumnIsRefusedNamingIt() {
        assertThatThrownBy(() -> run("booking_id,pnr,type,amount,date\nTJ-1,,SALE,1,2026-10-05\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("commission");
        verifyNoInteractions(auditService);
    }

    @Test
    void badValuesRefuseTheWholeFileWithTheLineNumber() {
        found(ticketed());
        assertThatThrownBy(() -> run(HEADER
                + "TJ-1,ABC123,SALE,10000,180,2026-10-05\n"
                + "TJ-1,ABC123,SALE,abc,180,2026-10-05\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Line 3").hasMessageContaining("amount");
        assertThatThrownBy(() -> run(HEADER + "TJ-1,ABC123,SALE,1,1,10/05/2026\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Line 2").hasMessageContaining("date");
        assertThatThrownBy(() -> run(HEADER + "TJ-1,ABC123,VOID,1,1,2026-10-05\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SALE or REFUND");
        assertThatThrownBy(() -> run(HEADER + "TJ-1,ABC123,SALE,1,-5,2026-10-05\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("commission");
        verify(bookingRepository, never()).save(any());
        verify(importRepository, never()).save(any());
    }

    @Test
    void onlyCsvUpToFiveMbWithAHeaderAndRowsIsAccepted() {
        assertThatThrownBy(() -> service.importStatement(
                new MockMultipartFile("file", "tj.xlsx", "application/octet-stream", "PK\u0003\u0004".getBytes()),
                SupplierEnvironment.PROD, "A1", "Admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CSV");
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        java.util.Arrays.fill(big, (byte) 'a');
        assertThatThrownBy(() -> service.importStatement(new MockMultipartFile("file", "tj.csv", "text/csv", big),
                SupplierEnvironment.PROD, "A1", "Admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("5 MB");
        assertThatThrownBy(() -> run(""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("header");
        assertThatThrownBy(() -> run(HEADER))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no lines");
    }

    @Test
    void theImportRecordsCountsAndWhoRanIt() {
        found(ticketed());
        run(HEADER + "TJ-1,ABC123,SALE,10000,180,2026-10-05\nTJ-NOPE,,SALE,1,1,2026-10-05\n");
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
