package com.voyra.crm.service;

import com.voyra.crm.dto.MemberResponse;
import com.voyra.crm.entity.Member;
import com.voyra.crm.entity.MemberDocument;
import com.voyra.crm.repository.MemberDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberMapperTest {

    @Mock MemberDocumentRepository memberDocumentRepository;
    @InjectMocks MemberMapper mapper;

    private static Member member(String id, String passport, LocalDate expiry) {
        return Member.builder().memberId(id).clientId("K1").name("Pax " + id).passportNumber(passport).passportExpiry(expiry)
                .dob(LocalDate.now().minusYears(30).minusDays(1)).build();
    }

    @Test
    void anEmptyRosterNeedsNoDocumentQuery() {
        assertThat(mapper.toResponsesWithDocuments(List.of())).isEmpty();
        verifyNoInteractions(memberDocumentRepository);
    }

    @Test
    void aRosterLoadsEveryMembersDocumentsInOneQuery() {
        MemberDocument doc = MemberDocument.builder().id("D1").memberId("M1").name("passport.pdf").docType("PASSPORT")
                .uploadedAt(LocalDateTime.of(2026, 10, 1, 9, 0)).build();
        when(memberDocumentRepository.findByMemberIdIn(List.of("M1", "M2"))).thenReturn(List.of(doc));

        List<MemberResponse> res = mapper.toResponsesWithDocuments(List.of(
                member("M1", "X0000001", LocalDate.now().plusYears(5)), member("M2", null, null)));

        assertThat(res.get(0).getDocuments()).singleElement().satisfies(d -> {
            assertThat(d.getId()).isEqualTo("D1");
            assertThat(d.getName()).isEqualTo("passport.pdf");
            assertThat(d.getDocType()).isEqualTo("PASSPORT");
        });
        assertThat(res.get(0).getCurrentAge()).isEqualTo(30);
        assertThat(res.get(0).getPassportActionNeeded()).isFalse();
        assertThat(res.get(1).getDocuments()).isEmpty();
        assertThat(res.get(1).getPassportActionNeeded()).isTrue();
    }

    @Test
    void aPassportThatIsBlankUndatedOrExpiringWithinSixMonthsNeedsAction() {
        assertThat(mapper.toResponse(member("M1", " ", LocalDate.now().plusYears(5)), List.of()).getPassportActionNeeded()).isTrue();
        assertThat(mapper.toResponse(member("M1", "X1", null), List.of()).getPassportActionNeeded()).isTrue();
        assertThat(mapper.toResponse(member("M1", "X1", LocalDate.now().plusMonths(5)), List.of()).getPassportActionNeeded()).isTrue();
        assertThat(mapper.toResponse(member("M1", "X1", LocalDate.now().plusMonths(7)), List.of()).getPassportActionNeeded()).isFalse();
    }
}
