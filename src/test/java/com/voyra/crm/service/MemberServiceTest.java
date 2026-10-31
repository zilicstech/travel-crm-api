package com.voyra.crm.service;

import com.voyra.crm.dto.DocumentResponse;
import com.voyra.crm.dto.MemberCreateRequest;
import com.voyra.crm.dto.MemberResponse;
import com.voyra.crm.dto.MemberUpdateRequest;
import com.voyra.crm.entity.Member;
import com.voyra.crm.entity.MemberDocument;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.MemberRelation;
import com.voyra.crm.enums.MemberType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.LeadMemberRepository;
import com.voyra.crm.repository.MemberDocumentRepository;
import com.voyra.crm.repository.MemberRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    @Mock ClientService clientService;
    @Mock MemberRepository memberRepository;
    @Mock MemberDocumentRepository memberDocumentRepository;
    @Mock LeadMemberRepository leadMemberRepository;
    @Mock FileStorageService fileStorageService;
    @Mock AuditService auditService;
    MemberService service;

    @BeforeEach
    void setUp() {
        service = new MemberService(clientService, memberRepository, memberDocumentRepository, leadMemberRepository,
                fileStorageService, new MemberMapper(memberDocumentRepository), auditService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("A1", "agent", UserType.AGENT, "T1"), null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Member member(MemberType type, MemberRelation relation) {
        Member m = Member.builder().memberId("M1").clientId("K1").name("Ravi").type(type).relation(relation).build();
        when(memberRepository.findByMemberIdAndClientId("M1", "K1")).thenReturn(Optional.of(m));
        return m;
    }

    @Test
    void listingChecksAccessAndMapsTheActiveRoster() {
        when(memberRepository.findByClientIdAndIsActiveTrue("K1")).thenReturn(List.of(Member.builder().memberId("M1").name("Ravi").build()));
        when(memberDocumentRepository.findByMemberIdIn(List.of("M1"))).thenReturn(List.of());

        assertThat(service.listMembers("K1")).extracting(MemberResponse::getName).containsExactly("Ravi");
        verify(clientService).findAccessibleClient("K1");
    }

    @Test
    void addingAMemberSavesAnActiveMemberAndAudits() {
        MemberCreateRequest r = new MemberCreateRequest();
        r.setName("Meera");
        r.setRelation(MemberRelation.SPOUSE);
        r.setEmail("pax-a@example.test");
        r.setCountryCode("+91");
        r.setPhone("0000000000");
        r.setDob(LocalDate.of(1990, 1, 1));
        r.setGender("F");
        r.setNationality("IN");
        r.setPassportNumber("X0000000");
        r.setPassportExpiry(LocalDate.of(2035, 1, 1));

        MemberResponse res = service.addMember("K1", r);

        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).save(saved.capture());
        Member m = saved.getValue();
        assertThat(m.getType()).isEqualTo(MemberType.MEMBER);
        assertThat(m.getIsActive()).isTrue();
        assertThat(m.getCreatedBy()).isEqualTo("A1");
        assertThat(m.getEmail()).isEqualTo("pax-a@example.test");
        assertThat(m.getPassportExpiry()).isEqualTo(LocalDate.of(2035, 1, 1));
        assertThat(res.getName()).isEqualTo("Meera");
        assertThat(res.getDocuments()).isEmpty();
        verify(auditService).recordCreate(AuditEntityType.MEMBER, m.getMemberId(), "Meera");
    }

    @Test
    void selfIsReservedForThePrimaryMember() {
        MemberCreateRequest r = new MemberCreateRequest();
        r.setName("Meera");
        r.setRelation(MemberRelation.SELF);

        assertThatThrownBy(() -> service.addMember("K1", r)).hasMessageStartingWith("SELF is reserved");
        verify(memberRepository, never()).save(any());
    }

    @Test
    void aRenameUpdatesEveryFieldAndResyncsTheManifests() {
        Member m = member(MemberType.MEMBER, MemberRelation.SON);
        when(memberDocumentRepository.findByMemberId("M1")).thenReturn(List.of());
        MemberUpdateRequest r = new MemberUpdateRequest();
        r.setName("Ravi Kumar");
        r.setRelation(MemberRelation.FRIEND);
        r.setEmail("pax-b@example.test");
        r.setCountryCode("+91");
        r.setPhone("1111111111");
        r.setDob(LocalDate.of(2001, 2, 3));
        r.setGender("M");
        r.setNationality("IN");
        r.setPassportNumber("Y0000000");
        r.setPassportExpiry(LocalDate.of(2034, 5, 6));

        MemberResponse res = service.updateMember("K1", "M1", r);

        assertThat(m.getName()).isEqualTo("Ravi Kumar");
        assertThat(m.getRelation()).isEqualTo(MemberRelation.FRIEND);
        assertThat(m.getEmail()).isEqualTo("pax-b@example.test");
        assertThat(m.getCountryCode()).isEqualTo("+91");
        assertThat(m.getPhone()).isEqualTo("1111111111");
        assertThat(m.getDob()).isEqualTo(LocalDate.of(2001, 2, 3));
        assertThat(m.getGender()).isEqualTo("M");
        assertThat(m.getNationality()).isEqualTo("IN");
        assertThat(m.getPassportNumber()).isEqualTo("Y0000000");
        assertThat(m.getPassportExpiry()).isEqualTo(LocalDate.of(2034, 5, 6));
        assertThat(m.getModifiedBy()).isEqualTo("A1");
        assertThat(res.getName()).isEqualTo("Ravi Kumar");
        verify(leadMemberRepository).updateMemberNameForMember("M1", "Ravi Kumar");
        verify(auditService).recordUpdate(eq(AuditEntityType.MEMBER), eq("M1"), eq("Ravi Kumar"), anyList());
    }

    @Test
    void anEchoedNameAndRelationChangeNothingAndDoNotResync() {
        Member m = member(MemberType.CLIENT, MemberRelation.SELF);
        when(memberDocumentRepository.findByMemberId("M1")).thenReturn(List.of());
        MemberUpdateRequest echo = new MemberUpdateRequest();
        echo.setName("Ravi");
        echo.setRelation(MemberRelation.SELF);

        service.updateMember("K1", "M1", echo);
        service.updateMember("K1", "M1", new MemberUpdateRequest());

        assertThat(m.getName()).isEqualTo("Ravi");
        verifyNoInteractions(leadMemberRepository);
    }

    @Test
    void thePrimaryMembersRelationIsFixedAndNoOneElseBecomesSelf() {
        member(MemberType.CLIENT, MemberRelation.SELF);
        MemberUpdateRequest toSpouse = new MemberUpdateRequest();
        toSpouse.setRelation(MemberRelation.SPOUSE);
        assertThatThrownBy(() -> service.updateMember("K1", "M1", toSpouse)).hasMessageStartingWith("The primary member's relation is fixed");

        Member other = Member.builder().memberId("M2").clientId("K1").type(MemberType.MEMBER).relation(MemberRelation.SON).build();
        when(memberRepository.findByMemberIdAndClientId("M2", "K1")).thenReturn(Optional.of(other));
        MemberUpdateRequest toSelf = new MemberUpdateRequest();
        toSelf.setRelation(MemberRelation.SELF);
        assertThatThrownBy(() -> service.updateMember("K1", "M2", toSelf)).hasMessageStartingWith("The primary member's relation is fixed");
        verify(memberRepository, never()).save(any());
    }

    @Test
    void anUnknownMemberIsRefused() {
        when(memberRepository.findByMemberIdAndClientId("M9", "K1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus("K1", "M9", true)).hasMessage("Member not found: M9");
    }

    @Test
    void thePrimaryMemberCannotBeDeactivatedButCanBeReactivated() {
        Member m = member(MemberType.CLIENT, MemberRelation.SELF);
        when(memberDocumentRepository.findByMemberId("M1")).thenReturn(List.of());

        assertThatThrownBy(() -> service.updateStatus("K1", "M1", false)).hasMessageStartingWith("The primary member cannot be deactivated");
        assertThat(service.updateStatus("K1", "M1", true).getIsActive()).isTrue();
        verify(memberRepository).save(m);
        verifyNoInteractions(leadMemberRepository);
    }

    @Test
    void aTravellerOnOpenLeadsMustBeDroppedThereFirst() {
        member(MemberType.MEMBER, MemberRelation.SON);
        when(leadMemberRepository.findOpenLeadDestinationsForMember("M1", LeadService.TERMINAL_STATUSES))
                .thenReturn(List.of("Goa"), List.of("Goa", "Dubai"));

        assertThatThrownBy(() -> service.updateStatus("K1", "M1", false))
                .hasMessage("Ravi is a traveller on the open lead Goa - drop them from Goa first");
        assertThatThrownBy(() -> service.updateStatus("K1", "M1", false))
                .hasMessage("Ravi is a traveller on the open leads Goa, Dubai - drop them from those leads first");
        verify(memberRepository, never()).save(any());
    }

    @Test
    void aMemberWithNoOpenLeadsIsDeactivated() {
        Member m = member(MemberType.MEMBER, MemberRelation.SON);
        when(leadMemberRepository.findOpenLeadDestinationsForMember("M1", LeadService.TERMINAL_STATUSES)).thenReturn(List.of());
        when(memberDocumentRepository.findByMemberId("M1")).thenReturn(List.of());

        assertThat(service.updateStatus("K1", "M1", false).getIsActive()).isFalse();
        assertThat(m.getModifiedBy()).isEqualTo("A1");
        verify(auditService).recordUpdate(eq(AuditEntityType.MEMBER), eq("M1"), eq("Ravi"), anyList());
    }

    @Test
    void aDocumentIsStoredUnderTheCallersTenant() {
        member(MemberType.MEMBER, MemberRelation.SON);
        MockMultipartFile file = new MockMultipartFile("file", "passport.pdf", "application/pdf", new byte[]{1});
        when(fileStorageService.store("T1", "member-documents", "M1", file)).thenReturn("key-1");

        DocumentResponse res = service.uploadDocument("K1", "M1", "PASSPORT", file);

        ArgumentCaptor<MemberDocument> saved = ArgumentCaptor.forClass(MemberDocument.class);
        verify(memberDocumentRepository).save(saved.capture());
        assertThat(saved.getValue().getFileKey()).isEqualTo("key-1");
        assertThat(saved.getValue().getContentType()).isEqualTo("application/pdf");
        assertThat(saved.getValue().getUploadedBy()).isEqualTo("A1");
        assertThat(res.getName()).isEqualTo("passport.pdf");
        assertThat(res.getDocType()).isEqualTo("PASSPORT");
    }

    @Test
    void documentsAreDeletedAndDownloadedOnlyForThatMember() {
        member(MemberType.MEMBER, MemberRelation.SON);
        MemberDocument doc = MemberDocument.builder().id("D1").memberId("M1").fileKey("key-1").name("passport.pdf")
                .contentType("application/pdf").build();
        when(memberDocumentRepository.findByIdAndMemberId("D1", "M1")).thenReturn(Optional.of(doc));
        when(memberDocumentRepository.findByIdAndMemberId("D9", "M1")).thenReturn(Optional.empty());
        ByteArrayResource bytes = new ByteArrayResource(new byte[]{1});
        when(fileStorageService.retrieve("key-1")).thenReturn(bytes);

        MemberService.DocumentContent content = service.downloadDocument("K1", "M1", "D1");
        service.deleteDocument("K1", "M1", "D1");

        assertThat(content.resource()).isSameAs(bytes);
        assertThat(content.filename()).isEqualTo("passport.pdf");
        assertThat(content.contentType()).isEqualTo("application/pdf");
        verify(memberDocumentRepository).delete(doc);
        assertThatThrownBy(() -> service.deleteDocument("K1", "M1", "D9")).hasMessage("Document not found: D9");
        assertThatThrownBy(() -> service.downloadDocument("K1", "M1", "D9")).hasMessage("Document not found: D9");
    }
}
