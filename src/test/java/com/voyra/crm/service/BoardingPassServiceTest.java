package com.voyra.crm.service;

import com.voyra.crm.dto.BoardingPassRowResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.BoardingPass;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingDocument;
import com.voyra.crm.entity.BookingPassenger;
import com.voyra.crm.enums.BoardingPassStatus;
import com.voyra.crm.enums.BookingDocumentType;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.exception.FieldValidationException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BoardingPassRepository;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.BookingPassengerRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.spec.CriteriaProbe;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.service.booking.PlatformCancelRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BoardingPassServiceTest {

    /** 21 Nov 20:00 UTC is already 22 Nov in India, so "tomorrow" is the 23rd. */
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 22);
    private static final LocalDate TOMORROW = TODAY.plusDays(1);

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingDocumentRepository documentRepository;
    @Mock private BookingPassengerRepository passengerRepository;
    @Mock private BoardingPassRepository repository;
    @Mock private BookingService bookingService;
    @Mock private BookingActivityService activityService;
    @Mock private PlatformCancelRequestService requestService;
    @Mock private AgentRepository agentRepository;
    @Mock private AuthorResolver authorResolver;
    @Mock private FileStorageService fileStorage;
    @InjectMocks private BoardingPassService service;

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(Instant.parse("2026-11-21T20:00:00Z"), ZoneOffset.UTC);
        as("T1", UserType.AGENCY_OWNER);
        when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("A1", "Vikram"));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repository.existsById(anyString())).thenReturn(false);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TransactionSynchronizationManager.clearSynchronization();
    }

    private static void as(String userId, UserType role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(userId, "user", role, "T1"), null, List.of()));
    }

    private static Booking flight(String id, LocalDate out, LocalDate back, String platformId) {
        return Booking.builder().id(id).bookingNumber(7L).type(BookingType.FLIGHT).bookingStatus(BookingStatus.CONFIRMED)
                .journeyDate(out).returnDate(back).platformBookingId(platformId).clientName("Ajay").agentName("Vikram")
                .destination("Delhi to Mumbai").pnr("ABC123").build();
    }

    private static BookingPassenger pax(String name) {
        return BookingPassenger.builder().passengerName(name).build();
    }

    private static void runAfterCommit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }

    // ---- list

    @Test
    @SuppressWarnings("unchecked")
    void tomorrowsFlightsComeWithTheirPassengersLegAndWhatWasDone() {
        Booking onward = flight("B1", TOMORROW, null, null);
        Booking back = flight("B2", TODAY.minusDays(3), TOMORROW, "PB2");
        Booking voucherOnly = flight("B3", TODAY.minusDays(3), null, null);
        when(documentRepository.findBookingIdsWithTicketOn("2026-11-23")).thenReturn(List.of("B3"));
        when(bookingRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(onward, back, voucherOnly)));
        BoardingPass done = BoardingPass.builder().id("P1").bookingId("B1").travelDate(TOMORROW).status(BoardingPassStatus.GENERATED)
                .fileName("bp.pdf").updatedByName("Vikram").build();
        when(repository.findByBookingIdInAndTravelDate(List.of("B1", "B2", "B3"), TOMORROW)).thenReturn(List.of(done));
        when(passengerRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(pax("Ajay Sharma")));
        when(passengerRepository.findByBookingIdOrderBySortOrderAsc("B2")).thenReturn(List.of(pax("Asha Rao"), pax("Ravi Rao")));
        when(requestService.cancelledOn("PB2", TOMORROW)).thenReturn(Set.of(0));

        PagedResponse<BoardingPassRowResponse> page = service.list(null, false, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(BoardingPassRowResponse::getLeg).containsExactly("ONWARD", "RETURN", "FLIGHT");
        BoardingPassRowResponse first = page.getContent().get(0);
        assertThat(first.getTravelDate()).isEqualTo(TOMORROW);
        assertThat(first.getStatus()).isEqualTo(BoardingPassStatus.GENERATED);
        assertThat(first.getBoardingPassId()).isEqualTo("P1");
        assertThat(first.getFileName()).isEqualTo("bp.pdf");
        assertThat(first.getPassengers()).containsExactly("Ajay Sharma");
        assertThat(first.getBookingRef()).isEqualTo("BK00000007");
        assertThat(first.getPnr()).isEqualTo("ABC123");
        BoardingPassRowResponse second = page.getContent().get(1);
        assertThat(second.getStatus()).isNull();
        assertThat(second.getPassengers()).containsExactly("Ravi Rao"); // Voyra cancelled Asha on that journey
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePendingListAsksOnlyForFlightsWithNothingDoneAndAnAgentIsScoped() {
        as("A1", UserType.AGENT);
        when(agentRepository.findById("A1")).thenReturn(Optional.of(Agent.builder().id("A1").manageableServices(List.of(ServiceType.FLIGHT)).build()));
        when(documentRepository.findBookingIdsWithTicketOn("2026-11-20")).thenReturn(List.of());
        when(bookingRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        assertThat(service.list(LocalDate.of(2026, 11, 20), true, PageRequest.of(0, 5)).getContent()).isEmpty();
        verify(repository, never()).findByBookingIdInAndTravelDate(any(), any());

        when(agentRepository.findById("A1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(null, true, PageRequest.of(0, 5))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theQueryIsAFlightOnTheDayWithNoVoyraRequestUnderWayAndNoBoardingPassYet() {
        CriteriaProbe probe = new CriteriaProbe();
        Specification<Booking> pastPending = BoardingPassService.travelling(TODAY.minusDays(1), List.of("B3"), true);
        assertThat(probe.render(pastPending)).isEqualTo("and(equal(root.type, FLIGHT), root.bookingStatus.in([CONFIRMED, COMPLETED]), "
                + "or(equal(root.journeyDate, 2026-11-21), equal(root.returnDate, 2026-11-21), root.id.in([B3])))");
        assertThat(probe.render(BoardingPassService.travelling(TOMORROW, List.of(), false))).isEqualTo("and(equal(root.type, FLIGHT), "
                + "root.bookingStatus.in([CONFIRMED]), or(equal(root.journeyDate, 2026-11-23), equal(root.returnDate, 2026-11-23)))");
        assertThat(probe.render(BoardingPassService.noBoardingPass(TOMORROW))).isEqualTo("not(exists(query.subquery(String)))");
        assertThat(probe.calls()).contains("equal(BoardingPass.bookingId, root.id)", "equal(BoardingPass.travelDate, 2026-11-23)");
    }

    // ---- one booking

    @Test
    void aBookingListsEachFlightDateOnceWithItsBoardingPassAndAnythingElseNone() {
        Booking b = flight("B1", TOMORROW, TOMORROW.plusDays(4), null);
        when(bookingService.findAccessibleBooking("B1")).thenReturn(b);
        when(documentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(
                BookingDocument.builder().docType(BookingDocumentType.E_TICKET).details(Map.of("journeyDate", "2026-11-23", "returnDate", "2026-11-30")).build(),
                BookingDocument.builder().docType(BookingDocumentType.E_TICKET).build(),
                BookingDocument.builder().docType(BookingDocumentType.E_TICKET).details(Map.of("journeyDate", "2026-11-23")).build(),
                BookingDocument.builder().docType(BookingDocumentType.OTHER).details(Map.of("journeyDate", "2026-12-25")).build()));
        when(repository.findByBookingId("B1")).thenReturn(List.of(BoardingPass.builder().id("P1").bookingId("B1").travelDate(TOMORROW)
                .status(BoardingPassStatus.NOT_NEEDED).reason("Checks in himself").build()));

        List<BoardingPassRowResponse> rows = service.forBooking("B1");

        assertThat(rows).extracting(BoardingPassRowResponse::getTravelDate).containsExactly(TOMORROW, TOMORROW.plusDays(4), LocalDate.of(2026, 11, 30));
        assertThat(rows.get(0).getReason()).isEqualTo("Checks in himself");
        assertThat(rows.get(2).getLeg()).isEqualTo("FLIGHT");

        when(bookingService.findAccessibleBooking("H1")).thenReturn(Booking.builder().id("H1").type(BookingType.HOTEL).build());
        assertThat(service.forBooking("H1")).isEmpty();
        when(bookingService.findAccessibleBooking("B9")).thenReturn(flight("B9", null, null, null));
        assertThat(service.forBooking("B9")).isEmpty();
    }

    // ---- mark, reset, file

    @Test
    void generatedWithAProofStoresTheFileAndReplacesAnOldOneAfterCommit() {
        when(bookingService.findAccessibleBooking("B1")).thenReturn(flight("B1", TOMORROW, null, null));
        when(repository.findByBookingIdAndTravelDate("B1", TOMORROW)).thenReturn(Optional.of(BoardingPass.builder().id("P1").bookingId("B1")
                .travelDate(TOMORROW).status(BoardingPassStatus.NOT_NEEDED).fileKey("old-key").build()));
        MockMultipartFile file = new MockMultipartFile("file", "bp.pdf", "application/pdf", new byte[]{1});
        when(fileStorage.store("T1", "boarding-passes", "B1", file)).thenReturn("new-key");

        BoardingPassRowResponse row = service.mark("B1", TOMORROW, BoardingPassStatus.GENERATED, "ignored for generated", file);

        assertThat(row.getStatus()).isEqualTo(BoardingPassStatus.GENERATED);
        assertThat(row.getFileName()).isEqualTo("bp.pdf");
        assertThat(row.getReason()).isNull();
        assertThat(row.getUpdatedByName()).isEqualTo("Vikram");
        verify(activityService).record("B1", "BOARDING_PASS_GENERATED", "Boarding pass generated for 23 Nov 2026 (bp.pdf)");
        verify(fileStorage, never()).delete(anyString());
        runAfterCommit();
        verify(fileStorage).delete("old-key");
    }

    @Test
    void notNeededTakesAnOptionalTrimmedReasonAndNoFile() {
        when(bookingService.findAccessibleBooking("B1")).thenReturn(flight("B1", TOMORROW, null, null));
        when(repository.findByBookingIdAndTravelDate("B1", TOMORROW)).thenReturn(Optional.empty());
        MockMultipartFile file = new MockMultipartFile("file", "bp.pdf", "application/pdf", new byte[]{1});

        BoardingPassRowResponse row = service.mark("B1", TOMORROW, BoardingPassStatus.NOT_NEEDED, "  Client checks in himself ", file);
        assertThat(row.getReason()).isEqualTo("Client checks in himself");
        assertThat(row.getFileName()).isNull();
        verify(fileStorage, never()).store(anyString(), anyString(), anyString(), any());
        verify(activityService).record("B1", "BOARDING_PASS_NOT_NEEDED", "Boarding pass not needed for 23 Nov 2026: Client checks in himself");

        service.mark("B1", TOMORROW, BoardingPassStatus.NOT_NEEDED, " ", null);
        service.mark("B1", TOMORROW, BoardingPassStatus.NOT_NEEDED, null, null);
        service.mark("B1", TOMORROW, BoardingPassStatus.GENERATED, null, null);
        service.mark("B1", TOMORROW, BoardingPassStatus.GENERATED, null, new MockMultipartFile("file", new byte[0]));
        verify(activityService, org.mockito.Mockito.times(2)).record("B1", "BOARDING_PASS_NOT_NEEDED", "Boarding pass not needed for 23 Nov 2026");
        verify(activityService, org.mockito.Mockito.times(2)).record("B1", "BOARDING_PASS_GENERATED", "Boarding pass generated for 23 Nov 2026");
        runAfterCommit();
        verify(fileStorage, never()).delete(anyString());
    }

    @Test
    void onlyAFlightDateOfTheBookingAndAShortReasonAreTaken() {
        when(bookingService.findAccessibleBooking("B1")).thenReturn(flight("B1", TOMORROW, null, null));
        assertThatThrownBy(() -> service.mark("B1", TODAY, BoardingPassStatus.NOT_NEEDED, null, null))
                .isInstanceOf(FieldValidationException.class).hasMessage("This booking has no flight on that date");
        assertThatThrownBy(() -> service.mark("B1", TOMORROW, BoardingPassStatus.NOT_NEEDED, "x".repeat(301), null))
                .hasMessage("Write at most 300 characters");
        when(bookingService.findAccessibleBooking("H1")).thenReturn(Booking.builder().id("H1").type(BookingType.HOTEL).journeyDate(TOMORROW).build());
        assertThatThrownBy(() -> service.mark("H1", TOMORROW, BoardingPassStatus.GENERATED, null, null)).isInstanceOf(FieldValidationException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void resetDeletesTheRowAndItsFileAfterCommitAndAnUnknownOrUnreachableRowIs404() {
        BoardingPass pass = BoardingPass.builder().id("P1").bookingId("B1").travelDate(TOMORROW).fileKey("k1").build();
        when(repository.findById("P1")).thenReturn(Optional.of(pass));
        service.reset("P1");
        verify(repository).delete(pass);
        verify(bookingService).findAccessibleBooking("B1");
        verify(activityService).record("B1", "BOARDING_PASS_RESET", "Boarding pass for 23 Nov 2026 set back to still to do");
        runAfterCommit();
        verify(fileStorage).delete("k1");

        assertThatThrownBy(() -> service.reset("P9")).isInstanceOf(NotFoundException.class);
        when(repository.findById("P2")).thenReturn(Optional.of(BoardingPass.builder().id("P2").bookingId("B2").travelDate(TOMORROW).build()));
        when(bookingService.findAccessibleBooking("B2")).thenThrow(new NotFoundException("Booking not found: B2"));
        assertThatThrownBy(() -> service.file("P2")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theProofFileIsServedWithItsNameAndTypeOr404WithoutOne() {
        when(repository.findById("P1")).thenReturn(Optional.of(BoardingPass.builder().id("P1").bookingId("B1").fileKey("k1")
                .fileName("bp.pdf").fileType("application/pdf").build()));
        when(fileStorage.retrieve("k1")).thenReturn(new ByteArrayResource(new byte[]{1}));
        BoardingPassService.ProofFile f = service.file("P1");
        assertThat(f.fileName()).isEqualTo("bp.pdf");
        assertThat(f.contentType()).isEqualTo("application/pdf");

        when(repository.findById("P2")).thenReturn(Optional.of(BoardingPass.builder().id("P2").bookingId("B1").build()));
        assertThatThrownBy(() -> service.file("P2")).isInstanceOf(NotFoundException.class).hasMessage("No file on this boarding pass");
        ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
        verify(bookingService, org.mockito.Mockito.atLeastOnce()).findAccessibleBooking(ids.capture());
        assertThat(ids.getAllValues()).containsOnly("B1");
        verify(fileStorage, never()).retrieve(eq("k2"));
    }
}
