package com.voyra.crm.service;

import com.voyra.crm.dto.LeadTimelineResponse;
import com.voyra.crm.entity.LeadTimeline;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.repository.LeadTimelineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeadTimelineServiceTest {

    @Mock private LeadTimelineRepository leadTimelineRepository;
    @Mock private AuthorResolver authorResolver;
    @InjectMocks private LeadTimelineService service;

    private List<LeadTimeline> saved(int times) {
        ArgumentCaptor<LeadTimeline> captor = ArgumentCaptor.forClass(LeadTimeline.class);
        verify(leadTimelineRepository, times(times)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void everyEventIsStampedWithTheSignedInAuthor() {
        when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("A1", "Priya"));

        service.record("L1", LeadTimelineEventType.NOTE_ADDED, "Note");
        service.record("L1", "S1", LeadTimelineEventType.BOOKING_LOGGED, "Booked");
        service.recordTransition("L1", LeadTimelineEventType.STATUS_CHANGED, LeadStatus.NEW, LeadStatus.CONTACTED, "Moved");

        List<LeadTimeline> rows = saved(3);
        assertThat(rows).extracting(LeadTimeline::getActorAgentId).containsOnly("A1");
        assertThat(rows).extracting(LeadTimeline::getActorName).containsOnly("Priya");
        assertThat(rows.get(0).getServiceId()).isNull();
        assertThat(rows.get(0).getFromStatus()).isNull();
        assertThat(rows.get(1).getServiceId()).isEqualTo("S1");
        assertThat(rows.get(1).getEventType()).isEqualTo(LeadTimelineEventType.BOOKING_LOGGED);
        assertThat(rows.get(2).getFromStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(rows.get(2).getToStatus()).isEqualTo(LeadStatus.CONTACTED);
        assertThat(rows.get(2).getDescription()).isEqualTo("Moved");
        assertThat(rows).extracting(LeadTimeline::getId).doesNotContainNull();
    }

    @Test
    void aCustomerActionNeedsNoSignedInUserAndLongTextIsCut() {
        service.recordCustomerAction("L1", "S1", LeadTimelineEventType.values()[0], "x".repeat(600));

        LeadTimeline row = saved(1).get(0);
        assertThat(row.getActorAgentId()).isEqualTo("customer");
        assertThat(row.getActorName()).isEqualTo("Customer");
        assertThat(row.getDescription()).hasSize(500).endsWith("...").startsWith("x".repeat(497));
        verifyNoInteractions(authorResolver);
    }

    @Test
    void aDescriptionOfExactlyTheColumnIsKept() {
        service.recordCustomerAction("L1", null, LeadTimelineEventType.values()[0], "y".repeat(500));
        assertThat(saved(1).get(0).getDescription()).isEqualTo("y".repeat(500));
    }

    @Test
    void aLeadsTimelineIsReadNewestFirst() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 1, 9, 0);
        when(leadTimelineRepository.findByLeadIdOrderByCreatedAtDesc("L1")).thenReturn(List.of(LeadTimeline.builder()
                .id("T1").serviceId("S1").eventType(LeadTimelineEventType.STATUS_CHANGED).fromStatus(LeadStatus.NEW)
                .toStatus(LeadStatus.LOST).actorAgentId("A1").actorName("Priya").description("Lost").createdAt(at).build()));

        List<LeadTimelineResponse> list = service.listForLead("L1");

        assertThat(list).singleElement().satisfies(r -> {
            assertThat(r.getId()).isEqualTo("T1");
            assertThat(r.getServiceId()).isEqualTo("S1");
            assertThat(r.getFromStatus()).isEqualTo(LeadStatus.NEW);
            assertThat(r.getToStatus()).isEqualTo(LeadStatus.LOST);
            assertThat(r.getActorName()).isEqualTo("Priya");
            assertThat(r.getDescription()).isEqualTo("Lost");
            assertThat(r.getCreatedAt()).isEqualTo(at);
        });
    }
}
