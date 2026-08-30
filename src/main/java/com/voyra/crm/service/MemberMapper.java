package com.voyra.crm.service;

import com.voyra.crm.dto.DocumentResponse;
import com.voyra.crm.dto.MemberResponse;
import com.voyra.crm.entity.Member;
import com.voyra.crm.entity.MemberDocument;
import com.voyra.crm.repository.MemberDocumentRepository;
import com.voyra.crm.util.PaxTypeCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared Member-to-DTO mapping.
 *
 * <p>Extracted into its own bean rather than duplicated because both ClientService and
 * MemberService return member payloads, and having ClientService depend on MemberService (or
 * the reverse) for one mapper would create a cycle the moment either grows a second reason to
 * call the other.
 *
 * <p>{@link #toResponsesWithDocuments} exists so a roster of any size costs one document query
 * instead of one per member - the same anti-N+1 posture as the snapshot columns.
 */
@Service
@RequiredArgsConstructor
public class MemberMapper {

    /** Most destinations refuse a passport expiring within six months of travel. */
    private static final int PASSPORT_VALIDITY_MONTHS = 6;

    private final MemberDocumentRepository memberDocumentRepository;

    public List<MemberResponse> toResponsesWithDocuments(List<Member> members) {
        if (members.isEmpty()) {
            return List.of();
        }
        Map<String, List<MemberDocument>> byMember = memberDocumentRepository
                .findByMemberIdIn(members.stream().map(Member::getMemberId).toList())
                .stream()
                .collect(Collectors.groupingBy(MemberDocument::getMemberId));

        return members.stream()
                .map(m -> toResponse(m, byMember.getOrDefault(m.getMemberId(), List.of())))
                .toList();
    }

    public MemberResponse toResponse(Member member, List<MemberDocument> documents) {
        return MemberResponse.builder()
                .memberId(member.getMemberId())
                .clientId(member.getClientId())
                .name(member.getName())
                .type(member.getType())
                .relation(member.getRelation())
                .email(member.getEmail())
                .countryCode(member.getCountryCode())
                .phone(member.getPhone())
                .dob(member.getDob())
                .gender(member.getGender())
                .nationality(member.getNationality())
                .passportNumber(member.getPassportNumber())
                .passportExpiry(member.getPassportExpiry())
                .currentAge(PaxTypeCalculator.ageAt(member.getDob(), LocalDate.now()))
                .passportActionNeeded(passportActionNeeded(member))
                .documents(documents.stream().map(MemberMapper::toDocumentResponse).toList())
                .isActive(member.getIsActive())
                .createdAt(member.getCreatedAt())
                .modifiedAt(member.getModifiedAt())
                .build();
    }

    /**
     * A passport that is missing, expired, or expiring inside the next six months will be
     * refused at check-in for most destinations, so both cases surface as one flag the agent
     * can act on rather than a raw date they have to reason about.
     */
    private boolean passportActionNeeded(Member member) {
        if (member.getPassportNumber() == null || member.getPassportNumber().isBlank()) {
            return true;
        }
        return member.getPassportExpiry() == null
                || member.getPassportExpiry().isBefore(LocalDate.now().plusMonths(PASSPORT_VALIDITY_MONTHS));
    }

    private static DocumentResponse toDocumentResponse(MemberDocument document) {
        return DocumentResponse.builder()
                .id(document.getId())
                .name(document.getName())
                .docType(document.getDocType())
                .uploadedDate(document.getUploadedAt())
                .build();
    }
}
