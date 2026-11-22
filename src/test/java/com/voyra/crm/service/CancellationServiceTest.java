package com.voyra.crm.service;

import com.voyra.crm.dto.CancelRequestResponse;
import com.voyra.crm.dto.CancellationDetailResponse;
import com.voyra.crm.dto.CancellationFollowUpRequest;
import com.voyra.crm.dto.CancellationFollowUpResponse;
import com.voyra.crm.dto.CancellationFollowUpUpdateRequest;
import com.voyra.crm.dto.CancellationRowResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.CancellationFollowUp;
import com.voyra.crm.entity.PlatformCancelRequest;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.CancelRequestStatus;
import com.voyra.crm.enums.CancellationFollowUpKind;
import com.voyra.crm.enums.CancellationStatus;
import com.voyra.crm.enums.FollowUpStatus;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.FieldValidationException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.CancellationFollowUpRepository;
import com.voyra.crm.repository.PlatformCancelRequestRepository;
import com.voyra.crm.repository.TenantRepository;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

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
class CancellationServiceTest {

    /** 22 Nov 2026 in India: 20:00 UTC on the 21st is already the 22nd at 01:30 IST. */
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 22);

    @Mock private BookingRepository bookingRepository;
    @Mock private CancellationFollowUpRepository followUpRepository;
    @Mock private PlatformCancelRequestRepository requestRepository;
    @Mock private PlatformCancelRequestService requestService;
    @Mock private BookingService bookingService;
    @Mock private BookingActivityService activityService;
    @Mock private AgentRepository agentRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private AuthorResolver authorResolver;
    @InjectMocks private CancellationService service;

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(Instant.parse("2026-11-21T20:00:00Z"), ZoneOffset.UTC);
        when(followUpRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(followUpRepository.existsById(anyString())).thenReturn(false);
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(Tenant.builder().id("T1").ownerName("Asha Owner").build()));
        when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("T1", "Asha Owner"));
        as("T1", UserType.AGENCY_OWNER);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void as(String userId, UserType role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(userId, "user", role, "T1"), null, List.of()));
    }

    private static Booking booking(String id, BookingStatus status, String platformId) {
        Booking b = Booking.builder().id(id).bookingNumber(42L).type(BookingType.FLIGHT).clientName("Ajay").destination("Dubai")
                .agentName("Vikram").bookingStatus(status).platformBookingId(platformId).departureDate(TODAY.plusDays(9)).build();
        b.setCancellationSupplierCharge(new BigDecimal("1000"));
        b.setCancellationAgencyFee(new BigDecimal("500"));
        b.setCustomerRefundDue(new BigDecimal("8500"));
        b.setRefundedTotalInr(BigDecimal.ZERO);
        return b;
    }

    private static CancellationFollowUp followUp(String id, String bookingId, CancellationFollowUpKind kind, LocalDate due, FollowUpStatus status) {
        return CancellationFollowUp.builder().id(id).bookingId(bookingId).kind(kind).dueDate(due).status(status)
                .assignedAgentId("A1").assignedAgentName("Vikram").note("Call the client").build();
    }

    private static CancellationFollowUpRequest request(CancellationFollowUpKind kind, LocalDate date, Integer inDays) {
        CancellationFollowUpRequest r = new CancellationFollowUpRequest();
        r.setKind(kind);
        r.setDueDate(date);
        r.setInDays(inDays);
        return r;
    }

    private static PlatformCancelRequest platformRequest(String platformId, CancelRequestStatus status) {
        PlatformCancelRequest r = new PlatformCancelRequest();
        r.setPlatformBookingId(platformId);
        r.setStatus(status);
        return r;
    }

    // ---- list

    @Test
    @SuppressWarnings("unchecked")
    void theOpenListMapsEachBookingWithItsNewestRequestAndEarliestOpenFollowUp() {
        Booking voyra = booking("B1", BookingStatus.CONFIRMED, "PB1");
        Booking own = booking("B2", BookingStatus.CANCELLED, null);
        own.setRefundCheckedAt(LocalDateTime.now());
        own.setCancellationAgencyFee(null);
        own.setSupplierRefundExpected(null);
        Pageable asked = PageRequest.of(1, 2);
        when(bookingRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(voyra, own), PageRequest.of(1, 2), 7));
        when(followUpRepository.findByBookingIdInOrderByDueDateAscCreatedAtAsc(List.of("B1", "B2"))).thenReturn(List.of(
                followUp("F1", "B2", CancellationFollowUpKind.FOLLOW_UP, TODAY.minusDays(1), FollowUpStatus.OPEN),
                followUp("F2", "B2", CancellationFollowUpKind.FOLLOW_UP, TODAY.plusDays(3), FollowUpStatus.OPEN),
                followUp("F3", "B2", CancellationFollowUpKind.FOLLOW_UP, TODAY.minusDays(9), FollowUpStatus.DONE)));
        when(requestRepository.findByPlatformBookingIdInOrderByCreatedAtAsc(List.of("PB1"))).thenReturn(List.of(
                platformRequest("PB1", CancelRequestStatus.REJECTED), platformRequest("PB1", CancelRequestStatus.OPEN)));

        PagedResponse<CancellationRowResponse> page = service.list(CancellationService.View.OPEN, "ajay", "A1", asked);

        assertThat(page.getTotalElements()).isEqualTo(7);
        CancellationRowResponse first = page.getContent().get(0);
        assertThat(first.getStatus()).isEqualTo(CancellationStatus.REQUESTED);
        assertThat(first.getVoyraRequestStatus()).isEqualTo(CancelRequestStatus.OPEN);
        assertThat(first.getNextFollowUp()).isNull();
        assertThat(first.getBookingRef()).isEqualTo("BK00000042");
        assertThat(first.getTravelDate()).isEqualTo(TODAY.plusDays(9));
        CancellationRowResponse second = page.getContent().get(1);
        assertThat(second.getStatus()).isEqualTo(CancellationStatus.REFUND_PENDING);
        assertThat(second.isFollowUpDue()).isTrue();
        assertThat(second.getNextFollowUp().getId()).isEqualTo("F1");
        assertThat(second.getCancellationCharges()).isEqualByComparingTo("1000");
        assertThat(second.getCustomerRefundDue()).isEqualByComparingTo("8500");
        assertThat(second.getRefundedToClient()).isEqualByComparingTo("0");
        assertThat(second.getSupplierRefundExpected()).isEqualByComparingTo("0");
        assertThat(second.isCrossChecked()).isTrue();
        ArgumentCaptor<Pageable> paged = ArgumentCaptor.forClass(Pageable.class);
        verify(bookingRepository).findAll(any(Specification.class), paged.capture());
        assertThat(paged.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "bookingNumber"));
        assertThat(paged.getValue().getPageNumber()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEmptyPageAsksNothingMoreAndAnAgentIsScopedToTheBookingsHeMayOpen() {
        as("A1", UserType.AGENT);
        when(agentRepository.findById("A1")).thenReturn(Optional.of(Agent.builder().id("A1").manageableServices(List.of(ServiceType.FLIGHT)).build()));
        when(bookingRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        assertThat(service.list(CancellationService.View.ALL, " ", " ", PageRequest.of(0, 20)).getContent()).isEmpty();
        verify(agentRepository).findById("A1");
        verify(followUpRepository, never()).findByBookingIdInOrderByDueDateAscCreatedAtAsc(any());
        verify(requestRepository, never()).findByPlatformBookingIdInOrderByCreatedAtAsc(any());

        when(agentRepository.findById("A1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(CancellationService.View.ALL, null, null, PageRequest.of(0, 20)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDueListIsSortedByTheEarliestDueDateAndPagedInMemory() {
        Booking requested = booking("B1", BookingStatus.CONFIRMED, "PB1");
        Booking later = booking("B2", BookingStatus.CONFIRMED, null);
        Booking soon = booking("B3", BookingStatus.CONFIRMED, null);
        when(bookingRepository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of(requested, later, soon));
        when(followUpRepository.findByBookingIdInOrderByDueDateAscCreatedAtAsc(List.of("B1", "B2", "B3"))).thenReturn(List.of(
                followUp("F2", "B2", CancellationFollowUpKind.SCHEDULED_CANCEL, TODAY, FollowUpStatus.OPEN),
                followUp("F3", "B3", CancellationFollowUpKind.SCHEDULED_CANCEL, TODAY.minusDays(2), FollowUpStatus.OPEN)));
        when(requestRepository.findByPlatformBookingIdInOrderByCreatedAtAsc(List.of("PB1"))).thenReturn(List.of(platformRequest("PB1", CancelRequestStatus.OPEN)));

        PagedResponse<CancellationRowResponse> first = service.list(CancellationService.View.DUE, null, null, PageRequest.of(0, 2));
        assertThat(first.getContent()).extracting(CancellationRowResponse::getBookingId).containsExactly("B3", "B1");
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getContent().get(0).getStatus()).isEqualTo(CancellationStatus.FOLLOW_UP_REQUIRED);

        PagedResponse<CancellationRowResponse> beyond = service.list(CancellationService.View.DUE, null, null, PageRequest.of(5, 2));
        assertThat(beyond.getContent()).isEmpty();
    }

    // ---- detail

    @Test
    void theDetailCarriesFollowUpsVoyraRequestsAndActivity() {
        Booking b = booking("B1", BookingStatus.CONFIRMED, "PB1");
        when(bookingService.findAccessibleBooking("B1")).thenReturn(b);
        when(followUpRepository.findByBookingIdOrderByDueDateAscCreatedAtAsc("B1")).thenReturn(List.of(
                followUp("F1", "B1", CancellationFollowUpKind.SCHEDULED_CANCEL, TODAY.plusDays(2), FollowUpStatus.OPEN)));
        CancelRequestResponse older = new CancelRequestResponse("R1", CancelRequestStatus.WITHDRAWN, null, null, false, List.of(), List.of(),
                null, List.of(), null, null, null, false, null, "Vikram", Instant.now(), null);
        CancelRequestResponse newest = new CancelRequestResponse("R2", CancelRequestStatus.IN_PROGRESS, null, null, false, List.of(), List.of(),
                null, List.of(), null, null, null, false, null, "Vikram", Instant.now(), null);
        when(requestService.listForCrmBooking("PB1")).thenReturn(List.of(older, newest));
        when(activityService.list("B1")).thenReturn(List.of());

        CancellationDetailResponse d = service.detail("B1");

        assertThat(d.getRow().getStatus()).isEqualTo(CancellationStatus.IN_PROGRESS);
        assertThat(d.getFollowUps()).extracting(CancellationFollowUpResponse::getId).containsExactly("F1");
        assertThat(d.getVoyraRequests()).hasSize(2);
        assertThat(d.getActivity()).isEmpty();

        Booking own = booking("B2", BookingStatus.CONFIRMED, null);
        when(bookingService.findAccessibleBooking("B2")).thenReturn(own);
        when(followUpRepository.findByBookingIdOrderByDueDateAscCreatedAtAsc("B2")).thenReturn(List.of());
        CancellationDetailResponse none = service.detail("B2");
        assertThat(none.getVoyraRequests()).isEmpty();
        assertThat(none.getRow().getStatus()).isEqualTo(CancellationStatus.NOT_CANCELLED);
        assertThat(none.getRow().getVoyraRequestStatus()).isNull();
    }

    // ---- add

    @Test
    void anOwnerSchedulesACancellationInTwoDaysForHimselfAndItIsRecorded() {
        when(bookingService.findAccessibleBooking("B1")).thenReturn(booking("B1", BookingStatus.CONFIRMED, null));
        CancellationFollowUpRequest r = request(CancellationFollowUpKind.SCHEDULED_CANCEL, null, 2);
        r.setNote("  Client decides Monday ");

        CancellationFollowUpResponse saved = service.addFollowUp("B1", r);

        assertThat(saved.getDueDate()).isEqualTo(TODAY.plusDays(2));
        assertThat(saved.getAssignedAgentId()).isEqualTo("T1");
        assertThat(saved.getAssignedAgentName()).isEqualTo("Asha Owner");
        assertThat(saved.getNote()).isEqualTo("Client decides Monday");
        assertThat(saved.getStatus()).isEqualTo(FollowUpStatus.OPEN);
        assertThat(saved.getCreatedByName()).isEqualTo("Asha Owner");
        verify(activityService).record("B1", "CANCELLATION_SCHEDULED", "Cancellation scheduled for 24 Nov 2026, Asha Owner: Client decides Monday");
    }

    @Test
    void aScheduledCancellationNeedsALiveBookingWithNoRequestUnderWay() {
        when(bookingService.findAccessibleBooking("B1")).thenReturn(booking("B1", BookingStatus.CANCELLED, null));
        CancellationFollowUpRequest r = request(CancellationFollowUpKind.SCHEDULED_CANCEL, TODAY, null);
        assertThatThrownBy(() -> service.addFollowUp("B1", r))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.getCode()).isEqualTo("NOT_CANCELLABLE"));

        when(bookingService.findAccessibleBooking("B2")).thenReturn(booking("B2", BookingStatus.CONFIRMED, "PB2"));
        when(requestRepository.existsByPlatformBookingIdAndStatusIn("PB2", CancelRequestStatus.ACTIVE)).thenReturn(true);
        assertThatThrownBy(() -> service.addFollowUp("B2", r)).isInstanceOf(BookingConflictException.class);
        verify(followUpRepository, never()).save(any());
    }

    @Test
    void aFollowUpNeedsACancellationToFollowUp() {
        when(bookingService.findAccessibleBooking("B1")).thenReturn(booking("B1", BookingStatus.CONFIRMED, null));
        when(followUpRepository.findByBookingIdOrderByDueDateAscCreatedAtAsc("B1")).thenReturn(List.of(
                followUp("F9", "B1", CancellationFollowUpKind.FOLLOW_UP, TODAY, FollowUpStatus.OPEN)));
        CancellationFollowUpRequest r = request(CancellationFollowUpKind.FOLLOW_UP, TODAY.plusDays(1), null);
        assertThatThrownBy(() -> service.addFollowUp("B1", r))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.getCode()).isEqualTo("NO_CANCELLATION"));

        // a Voyra booking that simply failed is not a cancellation, and a request alone is
        Booking failed = booking("B2", BookingStatus.CANCELLED, "PB2");
        failed.setCancelledBy(com.voyra.crm.security.SystemActor.USER_ID);
        when(bookingService.findAccessibleBooking("B2")).thenReturn(failed);
        assertThatThrownBy(() -> service.addFollowUp("B2", r)).isInstanceOf(BookingConflictException.class);

        when(bookingService.findAccessibleBooking("B3")).thenReturn(booking("B3", BookingStatus.CONFIRMED, "PB3"));
        when(requestRepository.findByPlatformBookingIdOrderByCreatedAtAsc("PB3")).thenReturn(List.of(platformRequest("PB3", CancelRequestStatus.REJECTED)));
        assertThat(service.addFollowUp("B3", r).getKind()).isEqualTo(CancellationFollowUpKind.FOLLOW_UP);
        verify(activityService).record(eq("B3"), eq("CANCELLATION_FOLLOW_UP"), eq("Follow-up added for 23 Nov 2026, Asha Owner"));
    }

    @Test
    void aFollowUpGoesOnACancelledBookingOrOneWithAScheduledCancellation() {
        when(bookingService.findAccessibleBooking("B1")).thenReturn(booking("B1", BookingStatus.CANCELLED, null));
        CancellationFollowUpRequest r = request(CancellationFollowUpKind.FOLLOW_UP, TODAY, null);
        assertThat(service.addFollowUp("B1", r).getDueDate()).isEqualTo(TODAY);

        Booking failedLooking = booking("B2", BookingStatus.CANCELLED, "PB2");
        failedLooking.setCancelledBy("A1"); // cancelled by a person: a real cancellation
        when(bookingService.findAccessibleBooking("B2")).thenReturn(failedLooking);
        assertThat(service.addFollowUp("B2", r)).isNotNull();

        when(bookingService.findAccessibleBooking("B3")).thenReturn(booking("B3", BookingStatus.CONFIRMED, null));
        when(followUpRepository.findByBookingIdOrderByDueDateAscCreatedAtAsc("B3")).thenReturn(List.of(
                followUp("F1", "B3", CancellationFollowUpKind.SCHEDULED_CANCEL, TODAY, FollowUpStatus.DONE)));
        assertThat(service.addFollowUp("B3", r)).isNotNull();
    }

    @Test
    void theDueDayIsADateOrSomeDaysAheadNeverBothNeitherOrPast() {
        when(bookingService.findAccessibleBooking("B1")).thenReturn(booking("B1", BookingStatus.CONFIRMED, null));
        assertThatThrownBy(() -> service.addFollowUp("B1", request(CancellationFollowUpKind.SCHEDULED_CANCEL, null, null)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getMessage()).isEqualTo("Pick a date, or after how many days"));
        assertThatThrownBy(() -> service.addFollowUp("B1", request(CancellationFollowUpKind.SCHEDULED_CANCEL, TODAY, 1)))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.addFollowUp("B1", request(CancellationFollowUpKind.SCHEDULED_CANCEL, TODAY.minusDays(1), null)))
                .hasMessage("Pick today or a later date");
    }

    @Test
    void itMayBeGivenToTheOwnerOrAnActiveAgentOnly() {
        when(bookingService.findAccessibleBooking("B1")).thenReturn(booking("B1", BookingStatus.CONFIRMED, null));
        when(agentRepository.findByIdAndTenantId("A1", "T1")).thenReturn(Optional.of(Agent.builder().id("A1").name("Vikram").userRole(UserType.AGENT).isActive(true).build()));
        when(agentRepository.findByIdAndTenantId("A2", "T1")).thenReturn(Optional.of(Agent.builder().id("A2").name("Old").userRole(UserType.AGENT).isActive(false).build()));
        when(agentRepository.findByIdAndTenantId("N1", "T1")).thenReturn(Optional.of(Agent.builder().id("N1").name("Neha").userRole(UserType.ACCOUNTANT).isActive(true).build()));
        CancellationFollowUpRequest r = request(CancellationFollowUpKind.SCHEDULED_CANCEL, TODAY, null);

        r.setAssignedAgentId("A1");
        assertThat(service.addFollowUp("B1", r).getAssignedAgentName()).isEqualTo("Vikram");
        r.setAssignedAgentId("T1");
        assertThat(service.addFollowUp("B1", r).getAssignedAgentName()).isEqualTo("Asha Owner");
        for (String nobody : List.of("A2", "N1", "X9")) {
            r.setAssignedAgentId(nobody);
            assertThatThrownBy(() -> service.addFollowUp("B1", r)).hasMessage("Pick the owner or an active agent");
        }
        r.setAssignedAgentId(" ");
        assertThat(service.addFollowUp("B1", r).getAssignedAgentId()).isEqualTo("T1");
    }

    // ---- update and complete

    @Test
    void anOpenFollowUpIsMovedGivenToSomeoneElseAndReNotedAndEachChangeIsRecorded() {
        when(bookingService.findAccessibleBooking("B1")).thenReturn(booking("B1", BookingStatus.CONFIRMED, null));
        CancellationFollowUp f = followUp("F1", "B1", CancellationFollowUpKind.SCHEDULED_CANCEL, TODAY, FollowUpStatus.OPEN);
        when(followUpRepository.findByIdAndBookingId("F1", "B1")).thenReturn(Optional.of(f));
        CancellationFollowUpUpdateRequest r = new CancellationFollowUpUpdateRequest();
        r.setDueDate(TODAY.plusDays(4));
        r.setAssignedAgentId("T1");
        r.setNote("Client asked for more time");

        CancellationFollowUpResponse saved = service.updateFollowUp("B1", "F1", r);

        assertThat(saved.getDueDate()).isEqualTo(TODAY.plusDays(4));
        assertThat(saved.getAssignedAgentName()).isEqualTo("Asha Owner");
        verify(activityService).record("B1", "CANCELLATION_FOLLOW_UP_CHANGED",
                "Scheduled cancellation moved to 26 Nov 2026, given to Asha Owner, note changed: Client asked for more time");
    }

    @Test
    void anUpdateThatChangesNothingRecordsNothingAndClearingTheNoteIsAChange() {
        when(bookingService.findAccessibleBooking("B1")).thenReturn(booking("B1", BookingStatus.CANCELLED, null));
        CancellationFollowUp f = followUp("F1", "B1", CancellationFollowUpKind.FOLLOW_UP, TODAY, FollowUpStatus.OPEN);
        when(followUpRepository.findByIdAndBookingId("F1", "B1")).thenReturn(Optional.of(f));
        CancellationFollowUpUpdateRequest same = new CancellationFollowUpUpdateRequest();
        same.setDueDate(TODAY);
        same.setAssignedAgentId("A1");
        same.setNote("Call the client");
        service.updateFollowUp("B1", "F1", same);
        service.updateFollowUp("B1", "F1", new CancellationFollowUpUpdateRequest());
        CancellationFollowUpUpdateRequest blank = new CancellationFollowUpUpdateRequest();
        blank.setAssignedAgentId("");
        service.updateFollowUp("B1", "F1", blank);
        verify(activityService, never()).record(anyString(), anyString(), anyString());

        when(agentRepository.findByIdAndTenantId("A3", "T1")).thenReturn(Optional.of(Agent.builder().id("A3").name("Ravi").userRole(UserType.AGENT).isActive(true).build()));
        CancellationFollowUpUpdateRequest reassign = new CancellationFollowUpUpdateRequest();
        reassign.setAssignedAgentId("A3");
        service.updateFollowUp("B1", "F1", reassign);
        verify(activityService).record("B1", "CANCELLATION_FOLLOW_UP_CHANGED", "Follow-up given to Ravi");

        CancellationFollowUpUpdateRequest clear = new CancellationFollowUpUpdateRequest();
        clear.setNote(" ");
        assertThat(service.updateFollowUp("B1", "F1", clear).getNote()).isNull();
        verify(activityService).record("B1", "CANCELLATION_FOLLOW_UP_CHANGED", "Follow-up note changed");
    }

    @Test
    void aFollowUpIsMarkedDoneOnceAndAnUnknownOneIs404() {
        when(bookingService.findAccessibleBooking("B1")).thenReturn(booking("B1", BookingStatus.CONFIRMED, null));
        when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("A1", "Vikram"));
        CancellationFollowUp f = followUp("F1", "B1", CancellationFollowUpKind.SCHEDULED_CANCEL, TODAY, FollowUpStatus.OPEN);
        when(followUpRepository.findByIdAndBookingId("F1", "B1")).thenReturn(Optional.of(f));

        CancellationFollowUpResponse done = service.completeFollowUp("B1", "F1");

        assertThat(done.getStatus()).isEqualTo(FollowUpStatus.DONE);
        assertThat(done.getDoneByName()).isEqualTo("Vikram");
        assertThat(done.getDoneAt()).isNotNull();
        verify(activityService).record("B1", "CANCELLATION_FOLLOW_UP_DONE", "Scheduled cancellation of 22 Nov 2026 done");
        assertThatThrownBy(() -> service.completeFollowUp("B1", "F1"))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.getCode()).isEqualTo("FOLLOW_UP_DONE"));
        assertThatThrownBy(() -> service.updateFollowUp("B1", "F1", new CancellationFollowUpUpdateRequest())).isInstanceOf(BookingConflictException.class);
        assertThatThrownBy(() -> service.completeFollowUp("B1", "F9")).isInstanceOf(NotFoundException.class);
    }
}
