package com.voyra.crm.service;

import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.ServiceStatus;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceBookingStatusSyncTest {

    @Mock private LeadServiceRepository leadServiceRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private LeadTimelineService leadTimelineService;
    @Mock private AuditService auditService;
    @Mock private LeadRepository leadRepository;
    @InjectMocks private ServiceBookingStatusSync sync;

    /** A booking logged on a service nobody claimed (the owner named the agent) makes it that
     *  agent's work - a booked service can't be accepted, so it must never be left without one. */
    @Test
    void aBookingOnAnUnclaimedServiceGivesItTheBookingsAgent() {
        LeadService service = LeadService.builder().id("S1").leadId("L1").type(ServiceType.FLIGHT)
                .status(ServiceStatus.NOT_STARTED).label("Flight").build();
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(service));

        sync.onBookingLogged("S1", "A1", "Ankita");

        assertThat(service.getStatus()).isEqualTo(ServiceStatus.BOOKED);
        assertThat(service.getAssignedAgentId()).isEqualTo("A1");
        assertThat(service.getAssignedAgentName()).isEqualTo("Ankita");
    }

    @Test
    void aClaimedServiceKeepsItsAgent() {
        LeadService service = LeadService.builder().id("S1").leadId("L1").type(ServiceType.FLIGHT)
                .status(ServiceStatus.IN_PROGRESS).label("Flight").assignedAgentId("A2").assignedAgentName("Rahul").build();
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(service));

        sync.onBookingLogged("S1", "A1", "Ankita");

        assertThat(service.getStatus()).isEqualTo(ServiceStatus.BOOKED);
        assertThat(service.getAssignedAgentId()).isEqualTo("A2");
    }

    /** The first booking on a lead converts it, so agent conversion rates mean something. */
    @Test
    void theFirstBookingMovesTheLeadToBooked() {
        LeadService service = LeadService.builder().id("S1").leadId("L1").type(ServiceType.HOTEL)
                .status(ServiceStatus.IN_PROGRESS).label("Hotel").assignedAgentId("A1").build();
        Lead lead = Lead.builder().id("L1").build();
        lead.setStatus(LeadStatus.NEGOTIATING);
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(service));
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));

        sync.onBookingLogged("S1", "A1", "Vikram");

        assertThat(lead.getStatus()).isEqualTo(LeadStatus.BOOKED);
    }

    @Test
    void aLostLeadIsNotRevivedByABooking() {
        LeadService service = LeadService.builder().id("S1").leadId("L1").type(ServiceType.HOTEL)
                .status(ServiceStatus.IN_PROGRESS).label("Hotel").assignedAgentId("A1").build();
        Lead lead = Lead.builder().id("L1").build();
        lead.setStatus(LeadStatus.LOST);
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(service));
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));

        sync.onBookingLogged("S1", "A1", "Vikram");

        assertThat(lead.getStatus()).isEqualTo(LeadStatus.LOST);
    }

    @Test
    void noServiceOrAGoneOrCalledOffServiceIsLeftAlone() {
        sync.onBookingLogged(null, "A1", "Ankita");
        sync.onBookingRemoved(null);
        when(leadServiceRepository.findById("S9")).thenReturn(Optional.empty());
        sync.onBookingLogged("S9", "A1", "Ankita");
        sync.onBookingRemoved("S9");
        LeadService cancelled = LeadService.builder().id("S1").leadId("L1").status(ServiceStatus.CANCELLED).build();
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(cancelled));

        sync.onBookingLogged("S1", "A1", "Ankita");
        sync.onBookingRemoved("S1");

        assertThat(cancelled.getStatus()).isEqualTo(ServiceStatus.CANCELLED);
        org.mockito.Mockito.verify(leadServiceRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(bookingRepository, leadRepository, leadTimelineService, auditService);
    }

    @Test
    void anUnclaimedServiceBookedWithNoAgentStaysUnclaimedAndAnAlreadyBookedLeadStaysBooked() {
        LeadService service = LeadService.builder().id("S1").leadId("L1").status(ServiceStatus.CONFIRMED).label("Visa").build();
        Lead lead = Lead.builder().id("L1").build();
        lead.setStatus(LeadStatus.BOOKED);
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(service));
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));

        sync.onBookingLogged("S1", null, null);

        assertThat(service.getStatus()).isEqualTo(ServiceStatus.BOOKED);
        assertThat(service.getAssignedAgentId()).isNull();
        org.mockito.Mockito.verify(leadRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aBookedServiceGoesBackToConfirmedOnlyWhenItsLastBookingGoes() {
        LeadService service = LeadService.builder().id("S1").leadId("L1").status(ServiceStatus.BOOKED).label("Hotel").build();
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(service));
        when(bookingRepository.countByServiceIdAndBookingStatusNot("S1", com.voyra.crm.enums.BookingStatus.CANCELLED)).thenReturn(1L, 0L);

        sync.onBookingRemoved("S1");
        assertThat(service.getStatus()).isEqualTo(ServiceStatus.BOOKED);

        sync.onBookingRemoved("S1");
        assertThat(service.getStatus()).isEqualTo(ServiceStatus.CONFIRMED);
        org.mockito.Mockito.verify(leadTimelineService).record("L1", "S1", com.voyra.crm.enums.LeadTimelineEventType.SERVICE_UPDATED,
                "Hotel Back to Confirmed - no booking remains logged");
    }
}
