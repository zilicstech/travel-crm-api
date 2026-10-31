package com.voyra.crm.service;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.dto.FeedbackSubmitRequest;
import com.voyra.crm.dto.PublicFeedbackResponse;
import com.voyra.crm.entity.FeedbackLink;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.FeedbackLinkRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A feedback link resolves its token, refuses expired or unavailable agencies, and works in the agency's schema. */
@ExtendWith(MockitoExtension.class)
class PublicFeedbackServiceTest {

    @Mock private FeedbackLinkRepository feedbackLinkRepository;
    @Mock private PublicFeedbackTenantService tenantService;
    @Mock private EntitlementService entitlementService;
    @InjectMocks private PublicFeedbackService service;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private FeedbackLink link(LocalDateTime expiresAt) {
        FeedbackLink l = FeedbackLink.builder().token("tok").tenantId("T1").bookingId("B1").clientId("C1").expiresAt(expiresAt).build();
        when(feedbackLinkRepository.findById("tok")).thenReturn(Optional.of(l));
        return l;
    }

    @Test
    void anUnknownOrExpiredTokenIsInvalid() {
        when(feedbackLinkRepository.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getFeedbackPage("nope"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("This feedback link is invalid or has expired");

        link(LocalDateTime.now().minusMinutes(1));
        assertThatThrownBy(() -> service.getFeedbackPage("tok"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("This feedback link is invalid or has expired");
        verifyNoInteractions(tenantService, entitlementService);
    }

    @Test
    void anAgencyWithoutPublicLinksIsNoLongerAvailable() {
        link(null);
        when(entitlementService.publicLinksAvailable("T1")).thenReturn(false);
        assertThatThrownBy(() -> service.submit("tok", new FeedbackSubmitRequest()))
                .isInstanceOf(NotFoundException.class).hasMessage("This link is no longer available");
        verifyNoInteractions(tenantService);
    }

    @Test
    void thePageIsReadInTheAgencysSchemaAndTheCallersTenantIsRestored() {
        link(LocalDateTime.now().plusDays(1));
        when(entitlementService.publicLinksAvailable("T1")).thenReturn(true);
        PublicFeedbackResponse page = PublicFeedbackResponse.builder().destination("Goa").build();
        doAnswer(i -> {
            assertThat(TenantContext.getTenantId()).isEqualTo("T1");
            return Optional.of(page);
        }).when(tenantService).fetchFeedbackPage("B1");
        TenantContext.setTenantId("OUTER");

        assertThat(service.getFeedbackPage("tok")).isSameAs(page);
        assertThat(TenantContext.getTenantId()).isEqualTo("OUTER");
    }

    @Test
    void aBookingGoneFromTheAgencyIsInvalidForBothReadAndSubmit() {
        link(null);
        when(entitlementService.publicLinksAvailable("T1")).thenReturn(true);
        when(tenantService.fetchFeedbackPage("B1")).thenReturn(Optional.empty());
        FeedbackSubmitRequest r = new FeedbackSubmitRequest();
        when(tenantService.submit("B1", "C1", r)).thenReturn(false);
        TenantContext.setTenantId(" ");

        assertThatThrownBy(() -> service.getFeedbackPage("tok")).hasMessage("This feedback link is invalid or has expired");
        assertThatThrownBy(() -> service.submit("tok", r)).hasMessage("This feedback link is invalid or has expired");
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void aSubmissionIsSavedForTheLinksBookingAndClient() {
        link(null);
        when(entitlementService.publicLinksAvailable("T1")).thenReturn(true);
        FeedbackSubmitRequest r = new FeedbackSubmitRequest();
        r.setRating(4);
        when(tenantService.submit("B1", "C1", r)).thenReturn(true);

        service.submit("tok", r);

        org.mockito.Mockito.verify(tenantService).submit("B1", "C1", r);
        assertThat(TenantContext.getTenantId()).isNull();
    }
}
