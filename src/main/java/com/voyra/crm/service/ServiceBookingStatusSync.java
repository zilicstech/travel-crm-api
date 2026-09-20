package com.voyra.crm.service;

import com.voyra.crm.entity.LeadService;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.enums.ServiceStatus;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.util.AuditSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

/**
 * Keeps a lead_service's status in sync with whether a live booking exists on it. Deliberately
 * NOT {@code REQUIRES_NEW}: both methods run inside the caller's own transaction (BookingService
 * create/updateStatus), so a rollback there undoes the status flip with it - the same
 * "join the caller's transaction" rule {@link BookingAccountingSync} follows.
 *
 * <p>{@code BOOKED} is a derived fact ("at least one live booking exists"), never a status a
 * human chooses - {@code ServiceInstanceService.setStatus} rejects it outright. That means this
 * class is the only place a service ever becomes or leaves {@code BOOKED}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ServiceBookingStatusSync {

    private static final Set<ServiceStatus> BOOKABLE_FROM = Set.of(
            ServiceStatus.NOT_STARTED, ServiceStatus.IN_PROGRESS, ServiceStatus.AWAITING_CLIENT, ServiceStatus.CONFIRMED);

    private final LeadServiceRepository leadServiceRepository;
    private final BookingRepository bookingRepository;
    private final LeadTimelineService leadTimelineService;
    private final AuditService auditService;

    /** Called after a booking is saved on a service. A cancelled service is left alone -
     *  logging a booking must never resurrect work that was called off. */
    @Transactional
    public void onBookingLogged(String serviceId) {
        if (serviceId == null) {
            return;
        }
        LeadService service = leadServiceRepository.findById(serviceId).orElse(null);
        if (service == null || !BOOKABLE_FROM.contains(service.getStatus())) {
            return;
        }
        transition(service, ServiceStatus.BOOKED, "Booked - a supplier booking has been logged");
    }

    /** Called after a booking is cancelled or removed. Reverts BOOKED -> CONFIRMED only once
     *  no live (non-cancelled) booking remains on the service. */
    @Transactional
    public void onBookingRemoved(String serviceId) {
        if (serviceId == null) {
            return;
        }
        LeadService service = leadServiceRepository.findById(serviceId).orElse(null);
        if (service == null || service.getStatus() != ServiceStatus.BOOKED) {
            return;
        }
        long remaining = bookingRepository.countByServiceIdAndBookingStatusNot(serviceId, BookingStatus.CANCELLED);
        if (remaining == 0) {
            transition(service, ServiceStatus.CONFIRMED, "Back to Confirmed - no booking remains logged");
        }
    }

    private void transition(LeadService service, ServiceStatus next, String description) {
        Map<String, String> before = AuditSnapshot.of(service, "status");
        service.setStatus(next);
        leadServiceRepository.save(service);
        leadTimelineService.record(service.getLeadId(), service.getId(), LeadTimelineEventType.SERVICE_UPDATED,
                service.getLabel() + " " + description);
        auditService.recordUpdate(AuditEntityType.LEAD_SERVICE, service.getId(), service.getLabel(),
                AuditSnapshot.diff(before, AuditSnapshot.of(service, "status")));
        log.info("Service status synced from bookings: serviceId={}, status={}", service.getId(), next);
    }
}
