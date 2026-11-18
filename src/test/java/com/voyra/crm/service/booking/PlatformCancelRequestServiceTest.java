package com.voyra.crm.service.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.AdminCancelRequestResponse;
import com.voyra.crm.dto.CancelRequestCompleteRequest;
import com.voyra.crm.dto.CancelRequestCreateRequest;
import com.voyra.crm.dto.CancelRequestResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformBookingEvent;
import com.voyra.crm.entity.PlatformCancelRequest;
import com.voyra.crm.entity.PlatformCancelRequestFile;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.CancelReason;
import com.voyra.crm.enums.CancelRequestStatus;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.BookingRequestException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.PlatformBookingEventRepository;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.repository.PlatformCancelRequestFileRepository;
import com.voyra.crm.repository.PlatformCancelRequestRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.service.FileStorageService;
import com.voyra.crm.service.NotificationService;
import com.voyra.crm.service.PlatformAuditService;
import com.voyra.crm.service.PlatformSettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cancellation by request. The booking: two adults on a DEL-BOM-DEL round trip, displayed 8000, commission 200 less TDS
 * 10 (190 to take back on a whole cancellation).
 */
class PlatformCancelRequestServiceTest {

    private static final String T = "T1";
    private static final String ID = "PB1";
    private static final String KEY = "dGVzdC1vbmx5LWFlcy1rZXktMzItYnl0ZXMtbG9uZyE=";
    private static final byte[] PDF = "%PDF-1.4 test".getBytes();

    private final PlatformCancelRequestRepository requests = mock(PlatformCancelRequestRepository.class);
    private final PlatformCancelRequestFileRepository files = mock(PlatformCancelRequestFileRepository.class);
    private final PlatformBookingRepository bookings = mock(PlatformBookingRepository.class);
    private final PlatformBookingService bookingService = mock(PlatformBookingService.class);
    private final PlatformBookingQueryService queryService = mock(PlatformBookingQueryService.class);
    private final PlatformBookingEventRepository eventRepository = mock(PlatformBookingEventRepository.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final PlatformAuditService audit = mock(PlatformAuditService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final PlatformSettingService settings = mock(PlatformSettingService.class);
    private final PaxCodec paxCodec = new PaxCodec(new ObjectMapper().findAndRegisterModules(), KEY);
    private final List<PlatformBookingEvent> events = new ArrayList<>();
    private final Map<String, PlatformCancelRequest> rows = new LinkedHashMap<>();
    private final Map<String, PlatformCancelRequestFile> stored = new HashMap<>();
    private PlatformBooking booking;
    private PlatformCancelRequestService service;

    @BeforeEach
    void setUp() {
        service = new PlatformCancelRequestService(requests, files, bookings, bookingService, queryService,
                new PlatformBookingEventRecorder(eventRepository, paxCodec), paxCodec, storage, tenants, agents, audit, notifications, settings,
                mock(PlatformTransactionManager.class));
        service.clock = Clock.fixed(Instant.parse("2026-11-18T06:00:00Z"), ZoneOffset.UTC);
        booking = flight("ADULT", "ADULT");
        when(bookings.findByIdForUpdate(ID)).thenAnswer(i -> Optional.of(booking));
        when(bookings.findById(ID)).thenAnswer(i -> Optional.of(booking));
        when(bookings.findByIdAndTenantId(ID, T)).thenAnswer(i -> Optional.of(booking));
        when(bookings.findAllById(any())).thenAnswer(i -> List.of(booking));
        when(bookings.save(any())).thenAnswer(i -> i.getArgument(0));
        when(bookingService.requireBooker()).thenAnswer(i -> me());
        when(bookingService.actedOn(any(), any())).thenAnswer(i -> i.<Optional<PlatformBooking>>getArgument(1).orElseThrow());
        when(bookingService.postCancelMoney(any(), any(), any(), anyString())).thenReturn(true);
        when(queryService.visible(ID)).thenAnswer(i -> booking);
        when(queryService.adminView(any())).thenReturn(null);
        when(eventRepository.save(any())).thenAnswer(i -> {
            events.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(requests.saveAndFlush(any())).thenAnswer(i -> keep(i.getArgument(0)));
        when(requests.save(any())).thenAnswer(i -> keep(i.getArgument(0)));
        when(requests.findById(anyString())).thenAnswer(i -> Optional.ofNullable(rows.get(i.<String>getArgument(0))));
        when(requests.findByIdForUpdate(anyString())).thenAnswer(i -> Optional.ofNullable(rows.get(i.<String>getArgument(0))));
        when(requests.findByPlatformBookingIdOrderByCreatedAtAsc(ID)).thenAnswer(i -> new ArrayList<>(rows.values()));
        when(requests.existsByPlatformBookingIdAndStatusIn(eq(ID), any()))
                .thenAnswer(i -> rows.values().stream().anyMatch(r -> CancelRequestStatus.ACTIVE.contains(r.getStatus())));
        when(files.save(any())).thenAnswer(i -> {
            PlatformCancelRequestFile f = i.getArgument(0);
            stored.put(f.getId(), f);
            return f;
        });
        when(files.findByRequestIdInOrderByCreatedAtAsc(any()))
                .thenAnswer(i -> stored.values().stream().filter(f -> i.<java.util.Collection<String>>getArgument(0).contains(f.getRequestId())).toList());
        when(files.findByIdAndRequestId(anyString(), anyString()))
                .thenAnswer(i -> Optional.ofNullable(stored.get(i.<String>getArgument(0))).filter(f -> f.getRequestId().equals(i.getArgument(1))));
        when(storage.store(anyString(), anyString(), anyString(), any())).thenAnswer(i -> "key-" + i.<MultipartFile>getArgument(3).getOriginalFilename());
        when(tenants.findById(T)).thenReturn(Optional.of(Tenant.builder().id(T).agencyName("Sky Travels").ownerName("Dana Owner").build()));
        when(tenants.findAllById(any())).thenReturn(List.of(Tenant.builder().id(T).agencyName("Sky Travels").build()));
        when(settings.voyraCancelFee()).thenReturn(new BigDecimal("100.00"));
        loginAs("U1", UserType.AGENT);
        when(agents.findById("U1")).thenReturn(Optional.of(Agent.builder().id("U1").name("Asha Agent").build()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private PlatformCancelRequest keep(PlatformCancelRequest r) {
        rows.put(r.getId(), r);
        return r;
    }

    private static CustomUserPrincipal me() {
        return (CustomUserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private static void loginAs(String userId, UserType type) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(userId, userId + "@agency.test", type, type == UserType.SUPER_ADMIN ? null : T), null));
    }

    private PlatformBooking flight(String... types) {
        List<Map<String, Object>> pax = new ArrayList<>();
        for (int i = 0; i < types.length; i++) {
            pax.add(Map.of("type", types[i], "title", "Mr", "firstName", "Pax" + i, "lastName", "Test"));
        }
        return PlatformBooking.builder().id(ID).tenantId(T).product(PricingProduct.FLIGHT).status(PlatformBookingStatus.TICKETED).pnr("ABC123")
                .displayed(new BigDecimal("8000.00")).agencyCommission(new BigDecimal("200.00")).tds(new BigDecimal("10.00"))
                .itinerary(CancelPartsTest.roundTrip()).pricingSnapshot(new HashMap<>(Map.of("travelDate", "2026-12-01")))
                .paxEnc(paxCodec.encrypt(Map.of("passengers", pax))).paxCount(types.length).build();
    }

    private PlatformBooking hotel() {
        return PlatformBooking.builder().id(ID).tenantId(T).product(PricingProduct.HOTEL).status(PlatformBookingStatus.TICKETED)
                .displayed(new BigDecimal("6000.00")).itinerary(Map.of("stay", Map.of("checkIn", "2026-11-19")))
                .paxCount(2).build();
    }

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("files", name, "application/pdf", bytes);
    }

    private CancelRequestResponse raise(CancelReason reason, List<Integer> pax, List<String> sectors) {
        return service.create(ID, new CancelRequestCreateRequest(reason, pax, sectors, "Please cancel"), List.of());
    }

    private CancelRequestCompleteRequest complete(List<Integer> pax, List<String> sectors, String supplierRefund, String recall, boolean exceeds) {
        return new CancelRequestCompleteRequest(CancelReason.FLIGHT_CANCELLED_BY_AIRLINE, pax, sectors, new BigDecimal(supplierRefund),
                new BigDecimal("500.00"), new BigDecimal("100.00"), new BigDecimal(recall), " AMD-1 ", " Done ", exceeds);
    }

    private List<String> eventTypes() {
        return events.stream().map(PlatformBookingEvent::getType).toList();
    }

    // ---- create

    @Test
    void anAgentAsksToCancelOnePassengerOnTheReturnWithProof() {
        CancelRequestResponse r = service.create(ID, new CancelRequestCreateRequest(CancelReason.MEDICALLY_UNFIT, List.of(1), List.of("d1"),
                "  Hospitalised  "), List.of(file("certificate.pdf", PDF), new MockMultipartFile("files", "empty.pdf", "application/pdf", new byte[0])));

        assertThat(r.status()).isEqualTo(CancelRequestStatus.OPEN);
        assertThat(r.passengers()).containsExactly(new CancelRequestResponse.Passenger(1, "Mr Pax1 Test", "ADULT"));
        assertThat(r.sectors()).extracting(s -> s.label() + " " + s.date()).containsExactly("BOM-DEL 2026-12-05");
        assertThat(r.remarks()).isEqualTo("Hospitalised");
        assertThat(r.requestedByName()).isEqualTo("Asha Agent");
        assertThat(r.files()).extracting(CancelRequestResponse.FileRef::name).containsExactly("certificate.pdf");
        assertThat(r.refund()).isNull();
        assertThat(r.note()).isNull();
        assertThat(eventTypes()).containsExactly("CANCEL_REQUESTED");
        verify(storage).store(eq("platform"), eq("cancel-requests/" + T), eq(r.id()), any());
        verify(notifications).cancelRequestRaised(eq(booking), any(), eq("Sky Travels"));
    }

    @Test
    void theOwnerIsNamedByTheAgencyAndAnyoneElseByTheirLogin() {
        loginAs(T, UserType.AGENCY_OWNER);
        assertThat(raise(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0), List.of()).requestedByName()).isEqualTo("Dana Owner");
        rows.clear();
        loginAs("U2", UserType.AGENT);
        when(agents.findById("U2")).thenReturn(Optional.of(Agent.builder().id("U2").name(" ").build()));
        assertThat(raise(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0), List.of()).requestedByName()).isEqualTo("U2@agency.test");
        rows.clear();
        loginAs("U3", UserType.AGENT);
        assertThat(raise(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0), List.of()).requestedByName()).isEqualTo("U3@agency.test");
    }

    @Test
    void aHotelIsCancelledWholeWhateverIsSent() {
        booking = hotel();
        CancelRequestResponse r = service.create(ID, new CancelRequestCreateRequest(CancelReason.HOTEL_CHANGE_OF_PLANS, List.of(9), List.of("x"), " "), null);
        assertThat(r.passengers()).extracting(CancelRequestResponse.Passenger::index).containsExactly(0, 1);
        assertThat(r.passengers()).extracting(CancelRequestResponse.Passenger::name).containsOnlyNulls();
        assertThat(r.sectors()).isEmpty();
        assertThat(r.remarks()).isNull();
    }

    @Test
    void aRequestIsRefusedForTheWrongProductAnOtherReasonWithoutWordsOrABookingNotTicketed() {
        assertThatThrownBy(() -> raise(CancelReason.HOTEL_CHANGE_OF_PLANS, List.of(0), List.of()))
                .isInstanceOf(BookingRequestException.class).hasMessage("Choose a reason for a flight booking");
        assertThatThrownBy(() -> service.create(ID, new CancelRequestCreateRequest(CancelReason.BEREAVEMENT, List.of(0), List.of(), null), List.of()))
                .extracting("code").isEqualTo("REMARKS_REQUIRED");
        assertThatThrownBy(() -> service.create(ID, new CancelRequestCreateRequest(CancelReason.BEREAVEMENT, List.of(0), List.of(), " "), List.of()))
                .extracting("code").isEqualTo("REMARKS_REQUIRED");
        booking.setStatus(PlatformBookingStatus.CANCELLED);
        assertThatThrownBy(() -> raise(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0), List.of())).extracting("code").isEqualTo("NOT_CANCELLABLE");
        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void oneActiveRequestPerBookingAndTheSelectionIsChecked() {
        raise(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0), List.of());
        assertThatThrownBy(() -> raise(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(1), List.of()))
                .isInstanceOf(BookingConflictException.class).extracting("code").isEqualTo("CANCEL_REQUEST_OPEN");
        rows.clear();
        assertThatThrownBy(() -> raise(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(), null)).extracting("code").isEqualTo("INVALID_SELECTION");
    }

    @Test
    void proofFilesAreAtMostFiveTenMegabytesInAllAndPdfOrImages() {
        List<MultipartFile> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(file("p" + i + ".pdf", PDF));
        }
        assertThatThrownBy(() -> service.create(ID, new CancelRequestCreateRequest(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0), List.of(), null), six))
                .extracting("code").isEqualTo("TOO_MANY_FILES");
        byte[] big = new byte[6 * 1024 * 1024];
        assertThatThrownBy(() -> service.create(ID, new CancelRequestCreateRequest(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0), List.of(), null),
                List.of(file("a.pdf", big), file("b.pdf", big)))).extracting("code").isEqualTo("FILES_TOO_LARGE");
        for (MultipartFile bad : List.of(file("x.exe", PDF), file("noextension", PDF), file("fake.pdf", "not a pdf".getBytes()),
                new MockMultipartFile("files", null, "application/pdf", PDF))) {
            assertThatThrownBy(() -> service.create(ID, new CancelRequestCreateRequest(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0), List.of(), null),
                    List.of(bad))).extracting("code").isEqualTo("FILE_TYPE");
        }
    }

    @Test
    void aVeryLongFileNameKeepsItsEnd() {
        String name = "x".repeat(300) + ".pdf";
        CancelRequestResponse r = service.create(ID, new CancelRequestCreateRequest(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0), List.of(), null),
                List.of(file(name, PDF)));
        assertThat(r.files().get(0).name()).hasSize(255).endsWith(".pdf");
    }

    // ---- list, withdraw, files

    @Test
    void theAgencyListsItsRequestsAndWithdrawsOneVoyraHasNotPickedUp() {
        String id = raiseId(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0), List.of());
        assertThat(service.list(ID)).extracting(CancelRequestResponse::id).containsExactly(id);

        assertThat(service.withdraw(ID, id).status()).isEqualTo(CancelRequestStatus.WITHDRAWN);
        assertThat(service.withdraw(ID, id).status()).isEqualTo(CancelRequestStatus.WITHDRAWN); // a repeat is the same answer
        assertThat(eventTypes()).containsExactly("CANCEL_REQUESTED", "CANCEL_REQUEST_WITHDRAWN");

        rows.get(id).setStatus(CancelRequestStatus.IN_PROGRESS);
        assertThatThrownBy(() -> service.withdraw(ID, id)).extracting("code").isEqualTo("CANCEL_REQUEST_LOCKED");
        assertThatThrownBy(() -> service.withdraw(ID, "nope")).isInstanceOf(NotFoundException.class);
        rows.get(id).setPlatformBookingId("OTHER");
        assertThatThrownBy(() -> service.withdraw(ID, id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aProofFileIsServedByItsExtensionOnlyFromItsOwnRequest() {
        CancelRequestResponse r = service.create(ID, new CancelRequestCreateRequest(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0), List.of(), null),
                List.of(file("proof.pdf", PDF)));
        String fileId = r.files().get(0).id();
        when(storage.retrieve("key-proof.pdf")).thenReturn(new ByteArrayResource(PDF));

        PlatformCancelRequestService.FileContent c = service.agencyFile(ID, r.id(), fileId);
        assertThat(c.contentType()).isEqualTo("application/pdf");
        assertThat(c.fileName()).isEqualTo("proof.pdf");

        stored.get(fileId).setFileName("proof.unknownext");
        assertThat(service.adminFile(r.id(), fileId).contentType()).isEqualTo("application/octet-stream");
        verify(audit).record(eq(PlatformAuditAction.CANCEL_REQUEST_FILE_VIEW), eq("CANCEL_REQUEST"), eq(r.id()), any());

        stored.get(fileId).setFileKey(null);
        assertThatThrownBy(() -> service.agencyFile(ID, r.id(), fileId)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.adminFile("nope", fileId)).isInstanceOf(NotFoundException.class);
    }

    // ---- admin

    @Test
    void theQueueListsActiveRequestsOldestFirstAndAnyStatusOnRequest() {
        String id = raiseId(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0), List.of());
        when(requests.findByStatusIn(any(), any(Pageable.class))).thenAnswer(i -> new PageImpl<>(new ArrayList<>(rows.values())));

        var page = service.adminList(null, null, null);
        assertThat(page.getContent()).singleElement().satisfies(a -> {
            assertThat(a.request().id()).isEqualTo(id);
            assertThat(a.agencyName()).isEqualTo("Sky Travels");
            assertThat(a.travelDate()).isEqualTo("2026-12-01");
            assertThat(a.urgent()).isFalse();
            assertThat(a.prefill()).isNull();
            assertThat(a.booking()).isNull();
        });
        assertThat(service.adminList(CancelRequestStatus.COMPLETED, 0, 10).getContent()).hasSize(1);
        assertThat(service.adminList(CancelRequestStatus.OPEN, 0, 10).getContent()).hasSize(1);
    }

    @Test
    void theDetailCarriesThePrefillsAndFlagsTravelWithinADay() {
        String id = raiseId(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0), List.of());
        booking.getPricingSnapshot().put("travelDate", "2026-11-19");

        AdminCancelRequestResponse a = service.adminGet(id);

        assertThat(a.urgent()).isTrue();
        // one of two adults on both sectors: half the fare, half the 190 commission
        assertThat(a.prefill().share()).isEqualByComparingTo("0.5");
        assertThat(a.prefill().softCap()).isEqualByComparingTo("4000.00");
        assertThat(a.prefill().hardCap()).isEqualByComparingTo("8000.00");
        assertThat(a.prefill().remainingRecall()).isEqualByComparingTo("190.00");
        assertThat(a.prefill().recall()).isEqualByComparingTo("95.00");
        assertThat(a.prefill().voyraFee()).isEqualByComparingTo("100.00");
        assertThatThrownBy(() -> service.adminGet("nope")).isInstanceOf(NotFoundException.class);
        when(bookings.findById(ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.adminGet(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void anAdminPicksARequestUpOnceAndCannotStartAFinishedOne() {
        String id = raiseId(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0), List.of());
        loginAs("SA1", UserType.SUPER_ADMIN);

        assertThat(service.start(id).request().status()).isEqualTo(CancelRequestStatus.IN_PROGRESS);
        service.start(id);
        assertThat(rows.get(id).getStartedBy()).isEqualTo("SA1");
        verify(audit).record(eq(PlatformAuditAction.CANCEL_REQUEST_START), eq("CANCEL_REQUEST"), eq(id), any());

        rows.get(id).setStatus(CancelRequestStatus.WITHDRAWN);
        assertThatThrownBy(() -> service.start(id)).extracting("code").isEqualTo("CANCEL_REQUEST_CLOSED");
        assertThatThrownBy(() -> service.start("nope")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void anAdminDeclinesWithANoteTheAgencyThenSees() {
        String id = raiseId(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0), List.of());
        loginAs("SA1", UserType.SUPER_ADMIN);

        AdminCancelRequestResponse a = service.reject(id, "  Not refundable  ");
        service.reject(id, "again");

        assertThat(a.request().status()).isEqualTo(CancelRequestStatus.REJECTED);
        assertThat(a.request().note()).isEqualTo("Not refundable");
        verify(notifications).cancelRequestRejected(eq(booking), any());
        rows.get(id).setStatus(CancelRequestStatus.COMPLETED);
        assertThatThrownBy(() -> service.reject(id, "x")).extracting("code").isEqualTo("CANCEL_REQUEST_CLOSED");
    }

    @Test
    void completingAPartialPostsTheRefundUnderTheRequestsOwnKeysAndKeepsTheBookingTicketed() {
        String id = raiseId(CancelReason.MEDICALLY_UNFIT, List.of(1), List.of("d1"));
        loginAs("SA1", UserType.SUPER_ADMIN);

        AdminCancelRequestResponse a = service.complete(id, complete(List.of(1), List.of("d1"), "1600.00", "47.50", false));

        CancelRequestResponse r = a.request();
        assertThat(r.status()).isEqualTo(CancelRequestStatus.COMPLETED);
        assertThat(r.finalReason()).isEqualTo(CancelReason.FLIGHT_CANCELLED_BY_AIRLINE);
        assertThat(r.reasonChanged()).isTrue();
        assertThat(r.refund()).isEqualByComparingTo("1500.00"); // 1600 less Voyra's 100
        assertThat(r.charges()).isEqualByComparingTo("500.00");
        assertThat(r.recall()).isEqualByComparingTo("47.50");
        assertThat(r.note()).isEqualTo("Done");
        assertThat(a.supplierReference()).isEqualTo("AMD-1");
        assertThat(a.prefill()).isNull();
        verify(bookingService).postCancelMoney(booking, new BigDecimal("1500.00"), new BigDecimal("47.50"), ":" + id);
        verify(bookingService).queueMirror(booking);
        verify(bookingService).scheduleMirror(booking);
        verify(notifications).cancelRequestCompleted(eq(booking), any(), eq(new BigDecimal("1500.00")));
        verify(audit).record(eq(PlatformAuditAction.CANCEL_REQUEST_COMPLETE), eq("CANCEL_REQUEST"), eq(id), any());
        assertThat(booking.getStatus()).isEqualTo(PlatformBookingStatus.TICKETED);
        assertThat(eventTypes()).contains("CANCEL_REQUEST_COMPLETED").doesNotContain("CANCELLED");

        // the rest, in a second request, cancels the booking with every refund so far
        String second = raiseId(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0, 1), List.of());
        AdminCancelRequestResponse last = service.complete(second, new CancelRequestCompleteRequest(CancelReason.TRAVEL_PLAN_CANCELLED,
                List.of(0, 1), List.of(), new BigDecimal("4000.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("142.50"), null, null, false));
        assertThat(last.request().reasonChanged()).isFalse();
        assertThat(booking.getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        assertThat(booking.getCancelRefund()).isEqualByComparingTo("5500.00");
        assertThat(eventTypes()).contains("CANCELLED");
    }

    /** Raised by the agent, then back to whoever was signed in (the admin, in the completion tests). */
    private String raiseId(CancelReason reason, List<Integer> pax, List<String> sectors) {
        var who = SecurityContextHolder.getContext().getAuthentication();
        loginAs("U1", UserType.AGENT);
        try {
            return raise(reason, pax, sectors).id();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(who);
        }
    }

    @Test
    void aClosedWalletCompletesWithTheRefundOfflineAndTheAgencyIsTold() {
        String id = raiseId(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0, 1), List.of());
        loginAs("SA1", UserType.SUPER_ADMIN);
        when(bookingService.postCancelMoney(any(), any(), any(), anyString())).thenReturn(false);

        CancelRequestResponse r = service.complete(id, complete(List.of(0, 1), List.of(), "3000.00", "190.00", false)).request();

        assertThat(r.refundOffline()).isTrue();
        assertThat(events.get(events.size() - 2).getPayload()).containsEntry("offline", true);
    }

    @Test
    void theRefundIsCappedByTheFareAndTheShareAndTheRecallByWhatIsLeft() {
        String id = raiseId(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0), List.of());
        loginAs("SA1", UserType.SUPER_ADMIN);

        assertThatThrownBy(() -> service.complete(id, complete(List.of(0), List.of(), "9000.00", "0", true))).extracting("code").isEqualTo("REFUND_TOO_HIGH");
        assertThatThrownBy(() -> service.complete(id, complete(List.of(0), List.of(), "5000.00", "0", false))).extracting("code").isEqualTo("EXCEEDS_SHARE");
        assertThatThrownBy(() -> service.complete(id, complete(List.of(0), List.of(), "100.00", "191.00", false))).extracting("code").isEqualTo("RECALL_TOO_HIGH");
        assertThatThrownBy(() -> service.complete(id, new CancelRequestCompleteRequest(CancelReason.HOTEL_OTHER, List.of(0), List.of(),
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, false))).extracting("code").isEqualTo("INVALID_REASON");
        verify(bookingService, never()).postCancelMoney(any(), any(), any(), anyString());

        AdminCancelRequestResponse a = service.complete(id, complete(List.of(0), List.of(), "5000.00", "0", true));
        assertThat(a.exceedsShare()).isTrue();
        // a supplier refund below Voyra's fee refunds nothing
        String next = raiseId(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(1), List.of());
        assertThat(service.complete(next, complete(List.of(1), List.of(), "50.00", "0", false)).request().refund()).isEqualByComparingTo("0.00");
    }

    @Test
    void aRetryOfACompletionInFlightReplaysTheSameFiguresOnly() {
        String id = raiseId(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0), List.of());
        loginAs("SA1", UserType.SUPER_ADMIN);
        doThrow(new IllegalStateException("wallet down")).when(bookingService).postCancelMoney(any(), any(), any(), anyString());
        CancelRequestCompleteRequest figures = complete(List.of(0), List.of(), "1000.00", "10.00", false);
        assertThatThrownBy(() -> service.complete(id, figures)).hasMessage("wallet down");
        assertThat(rows.get(id).getStatus()).isEqualTo(CancelRequestStatus.COMPLETING);

        assertThatThrownBy(() -> service.complete(id, complete(List.of(0), List.of(), "1100.00", "10.00", false))).extracting("code").isEqualTo("FIGURES_LOCKED");
        assertThatThrownBy(() -> service.complete(id, complete(List.of(0), List.of(), "1000.00", "11.00", false))).extracting("code").isEqualTo("FIGURES_LOCKED");
        assertThatThrownBy(() -> service.complete(id, complete(List.of(1), List.of(), "1000.00", "10.00", false))).extracting("code").isEqualTo("FIGURES_LOCKED");
        assertThatThrownBy(() -> service.complete(id, complete(List.of(0), List.of("d0"), "1000.00", "10.00", false))).extracting("code").isEqualTo("FIGURES_LOCKED");
        assertThatThrownBy(() -> service.complete(id, new CancelRequestCompleteRequest(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0), List.of(),
                new BigDecimal("1000.00"), new BigDecimal("500.00"), new BigDecimal("100.00"), new BigDecimal("10.00"), null, null, false)))
                .extracting("code").isEqualTo("FIGURES_LOCKED");

        org.mockito.Mockito.doReturn(true).when(bookingService).postCancelMoney(any(), any(), any(), anyString());
        assertThat(service.complete(id, figures).request().status()).isEqualTo(CancelRequestStatus.COMPLETED);
        // completed: a repeat returns it as it is, and posts nothing more
        service.complete(id, figures);
        verify(notifications).cancelRequestCompleted(any(), any(), any());
    }

    @Test
    void aBookingCancelledAnotherWayDeclinesItsRequestWithoutMoney() {
        String id = raiseId(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0), List.of());
        loginAs("SA1", UserType.SUPER_ADMIN);
        booking.setStatus(PlatformBookingStatus.CANCELLED);

        AdminCancelRequestResponse a = service.complete(id, complete(List.of(0), List.of(), "1000.00", "0", false));

        assertThat(a.request().status()).isEqualTo(CancelRequestStatus.REJECTED);
        assertThat(a.request().note()).isEqualTo(PlatformCancelRequestService.CANCELLED_ANOTHER_WAY);
        verify(bookingService, never()).postCancelMoney(any(), any(), any(), anyString());
        assertThat(service.complete(id, complete(List.of(0), List.of(), "1000.00", "0", false)).request().status()).isEqualTo(CancelRequestStatus.REJECTED);
        rows.get(id).setStatus(CancelRequestStatus.WITHDRAWN);
        assertThatThrownBy(() -> service.complete(id, complete(List.of(0), List.of(), "1000.00", "0", false))).extracting("code").isEqualTo("CANCEL_REQUEST_CLOSED");
    }

    @Test
    void aCompletionAnotherInstanceFinishedFirstIsNotSettledTwice() {
        String id = raiseId(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0), List.of());
        loginAs("SA1", UserType.SUPER_ADMIN);
        when(bookingService.postCancelMoney(any(), any(), any(), anyString())).thenAnswer(i -> {
            rows.get(id).setStatus(CancelRequestStatus.COMPLETED);
            return true;
        });

        service.complete(id, complete(List.of(0), List.of(), "1000.00", "0", false));

        verify(bookingService, never()).scheduleMirror(any());
        verify(notifications, never()).cancelRequestCompleted(any(), any(), any());
    }

    @Test
    void aHotelBookingIsCompletedWholeWithTheStaysHeads() {
        booking = hotel();
        String id = service.create(ID, new CancelRequestCreateRequest(CancelReason.HOTEL_OTHER, null, null, "Guest asked"), null).id();
        loginAs("SA1", UserType.SUPER_ADMIN);
        AdminCancelRequestResponse detail = service.adminGet(id);
        assertThat(detail.urgent()).isTrue();
        assertThat(detail.prefill().recall()).isEqualByComparingTo("0.00"); // no commission on record

        service.complete(id, new CancelRequestCompleteRequest(CancelReason.HOTEL_ASKED_TO_CANCEL, null, null, new BigDecimal("6000.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, false));

        assertThat(booking.getStatus()).isEqualTo(PlatformBookingStatus.CANCELLED);
        assertThat(booking.getCancelRefund()).isEqualByComparingTo("6000.00");
    }

    @Test
    void aFinishedRequestHasNoPrefillAndABookingWithoutAFareCapsAtNothing() {
        booking.setDisplayed(null);
        String id = raiseId(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0), List.of());
        assertThat(service.adminGet(id).prefill().hardCap()).isEqualByComparingTo("0");
        booking.setStatus(PlatformBookingStatus.CANCELLED);
        assertThat(service.adminGet(id).prefill()).isNull();
        rows.get(id).setStatus(CancelRequestStatus.REJECTED);
        assertThat(service.adminGet(id).prefill()).isNull();
        assertThat(service.adminGet(id).urgent()).isFalse();
    }

    // ---- purge, travel date

    @Test
    void personalDataOfFinishedRequestsIsClearedAndTheirFilesDeleted() {
        CancelRequestResponse r = service.create(ID, new CancelRequestCreateRequest(CancelReason.TRAVEL_PLAN_CANCELLED, List.of(0), List.of(), "Hello"),
                List.of(file("a.pdf", PDF), file("b.pdf", PDF)));
        PlatformCancelRequest row = rows.get(r.id());
        row.setAdminNote("Note");
        row.setStatus(CancelRequestStatus.COMPLETED);
        when(requests.findByFinishedAtBeforeAndPersonalDataPurgedAtIsNull(any())).thenReturn(List.of(row));
        doThrow(new IllegalStateException("gone")).when(storage).delete("key-a.pdf");

        assertThat(service.purgePersonalData(Instant.now())).isEqualTo(1);

        assertThat(row.getRemarks()).isNull();
        assertThat(row.getAdminNote()).isNull();
        assertThat(row.getPersonalDataPurgedAt()).isNotNull();
        assertThat(stored.values()).allSatisfy(f -> assertThat(f.getFileKey()).isNull());
        verify(storage).delete("key-b.pdf");
    }

    @Test
    void theTravelDateIsTheReviewStampElseTheCheckIn() {
        assertThat(PlatformCancelRequestService.travelDate(PlatformBooking.builder().pricingSnapshot(Map.of("travelDate", "2026-12-01T10:00")).build()))
                .isEqualTo("2026-12-01");
        assertThat(PlatformCancelRequestService.travelDate(PlatformBooking.builder().itinerary(Map.of("stay", Map.of("checkIn", "2026-12-02"))).build()))
                .isEqualTo("2026-12-02");
        assertThat(PlatformCancelRequestService.travelDate(PlatformBooking.builder().itinerary(Map.of("stay", "x")).build())).isNull();
        assertThat(PlatformCancelRequestService.travelDate(PlatformBooking.builder().pricingSnapshot(Map.of("travelDate", "soon")).build())).isNull();
        assertThat(PlatformCancelRequestService.travelDate(PlatformBooking.builder().build())).isNull();
    }
}
