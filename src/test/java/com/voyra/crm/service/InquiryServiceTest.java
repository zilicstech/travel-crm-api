package com.voyra.crm.service;

import com.voyra.crm.dto.InquiryCreateRequest;
import com.voyra.crm.entity.Inquiry;
import com.voyra.crm.repository.InquiryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InquiryServiceTest {

    @Mock
    private InquiryRepository inquiryRepository;
    @InjectMocks
    private InquiryService inquiryService;

    private static InquiryCreateRequest request(String email, String phone) {
        InquiryCreateRequest r = new InquiryCreateRequest();
        r.setSource("pricing-modal");
        r.setName("  John Davis ");
        r.setEmail(email);
        r.setPhone(phone);
        r.setCompany("");
        r.setPlan("Growth Plan");
        return r;
    }

    @Test
    void submitStoresTrimmedInquiryWithBlanksAsNull() {
        assertThat(inquiryService.submit(request("john@acme.com", " "))).isTrue();

        ArgumentCaptor<Inquiry> saved = ArgumentCaptor.forClass(Inquiry.class);
        verify(inquiryRepository).save(saved.capture());
        Inquiry i = saved.getValue();
        assertThat(i.getId()).hasSize(36);
        assertThat(i.getSource()).isEqualTo("pricing-modal");
        assertThat(i.getName()).isEqualTo("John Davis");
        assertThat(i.getEmail()).isEqualTo("john@acme.com");
        assertThat(i.getPhone()).isNull();
        assertThat(i.getCompany()).isNull();
        assertThat(i.getPlan()).isEqualTo("Growth Plan");
    }

    @Test
    void phoneAloneIsEnoughContact() {
        assertThat(inquiryService.submit(request(null, "+91 98765 43210"))).isTrue();
        verify(inquiryRepository).save(any());
    }

    @Test
    void submitWithNeitherEmailNorPhoneIsRejected() {
        assertThatThrownBy(() -> inquiryService.submit(request("", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email or phone is required");
        verify(inquiryRepository, never()).save(any());
    }

    @Test
    void honeypotSubmissionIsDroppedWithoutSaving() {
        InquiryCreateRequest r = request("bot@spam.com", null);
        r.setWebsite("http://spam.example");
        assertThat(inquiryService.submit(r)).isFalse();
        verify(inquiryRepository, never()).save(any());
    }

    @Test
    void listSearchesCaseInsensitivelyAndPlainListOtherwise() {
        PageRequest pageable = PageRequest.of(0, 25);
        when(inquiryRepository.search("%acme%", pageable)).thenReturn(Page.empty(pageable));
        when(inquiryRepository.findAllByOrderByCreatedAtDesc(pageable)).thenReturn(Page.empty(pageable));

        inquiryService.list(" ACME ", pageable);
        inquiryService.list("  ", pageable);

        verify(inquiryRepository).search("%acme%", pageable);
        verify(inquiryRepository).findAllByOrderByCreatedAtDesc(pageable);
    }

    @Test
    void listedInquiriesCarryEveryField() {
        PageRequest pageable = PageRequest.of(0, 25);
        java.time.Instant at = java.time.Instant.parse("2026-10-01T09:00:00Z");
        com.voyra.crm.entity.Inquiry i = com.voyra.crm.entity.Inquiry.builder().id("Q1").source("pricing-modal").name("Pax A")
                .email("pax-a@example.test").phone("0000000000").company("Acme").plan("Growth").message("Call me").createdAt(at).build();
        when(inquiryRepository.findAllByOrderByCreatedAtDesc(pageable)).thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(i), pageable, 1));

        com.voyra.crm.dto.InquiryResponse r = inquiryService.list(null, pageable).getContent().get(0);

        org.assertj.core.api.Assertions.assertThat(r.getId()).isEqualTo("Q1");
        org.assertj.core.api.Assertions.assertThat(r.getSource()).isEqualTo("pricing-modal");
        org.assertj.core.api.Assertions.assertThat(r.getName()).isEqualTo("Pax A");
        org.assertj.core.api.Assertions.assertThat(r.getEmail()).isEqualTo("pax-a@example.test");
        org.assertj.core.api.Assertions.assertThat(r.getPhone()).isEqualTo("0000000000");
        org.assertj.core.api.Assertions.assertThat(r.getCompany()).isEqualTo("Acme");
        org.assertj.core.api.Assertions.assertThat(r.getPlan()).isEqualTo("Growth");
        org.assertj.core.api.Assertions.assertThat(r.getMessage()).isEqualTo("Call me");
        org.assertj.core.api.Assertions.assertThat(r.getCreatedAt()).isEqualTo(at);
    }
}
