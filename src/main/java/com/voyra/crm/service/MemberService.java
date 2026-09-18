package com.voyra.crm.service;

import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.dto.DocumentResponse;
import com.voyra.crm.dto.MemberCreateRequest;
import com.voyra.crm.dto.MemberResponse;
import com.voyra.crm.dto.MemberUpdateRequest;
import com.voyra.crm.entity.Member;
import com.voyra.crm.entity.MemberDocument;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.MemberRelation;
import com.voyra.crm.enums.MemberType;
import com.voyra.crm.repository.LeadMemberRepository;
import com.voyra.crm.repository.MemberDocumentRepository;
import com.voyra.crm.repository.MemberRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.AuditSnapshot;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** A client's roster: the people who travel, and the identity documents that let them. */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemberService {

    private static final String MEMBER_DOC_CATEGORY = "member-documents";
    private static final String[] AUDITED = {
            "name", "relation", "email", "countryCode", "phone", "dob", "gender",
            "nationality", "passportNumber", "passportExpiry", "isActive"
    };

    private final ClientService clientService;
    private final MemberRepository memberRepository;
    private final MemberDocumentRepository memberDocumentRepository;
    private final LeadMemberRepository leadMemberRepository;
    private final FileStorageService fileStorageService;
    private final MemberMapper memberMapper;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<MemberResponse> listMembers(String clientId) {
        clientService.findAccessibleClient(clientId);
        return memberMapper.toResponsesWithDocuments(memberRepository.findByClientIdAndIsActiveTrue(clientId));
    }

    @Transactional
    public MemberResponse addMember(String clientId, MemberCreateRequest request) {
        clientService.findAccessibleClient(clientId);
        return memberMapper.toResponse(persistMember(clientId, request), List.of());
    }

    /**
     * Package-private so LeadService can create an ad-hoc traveller and attach them to a lead
     * inside one transaction, without a second access check on a client it has already
     * resolved. Callers outside this package must go through {@link #addMember}.
     */
    Member persistMember(String clientId, MemberCreateRequest request) {
        if (request.getRelation() == MemberRelation.SELF) {
            throw new IllegalArgumentException(
                    "SELF is reserved for the client's primary member and cannot be assigned to another member");
        }
        Member member = Member.builder()
                .memberId(UniqueIdResolver.resolve(memberRepository::existsById))
                .clientId(clientId)
                .name(request.getName())
                .relation(request.getRelation())
                .type(MemberType.MEMBER)
                .email(request.getEmail())
                .countryCode(request.getCountryCode())
                .phone(request.getPhone())
                .dob(request.getDob())
                .gender(request.getGender())
                .nationality(request.getNationality())
                .passportNumber(request.getPassportNumber())
                .passportExpiry(request.getPassportExpiry())
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .createdBy(currentUserId())
                .build();
        memberRepository.save(member);
        auditService.recordCreate(AuditEntityType.MEMBER, member.getMemberId(), member.getName());
        log.info("Member added: clientId={}, memberId={}, relation={}",
                clientId, member.getMemberId(), member.getRelation());
        return member;
    }

    /**
     * Renaming a member re-syncs the {@code member_name} snapshot on every lead manifest row
     * they appear on, in the same transaction - otherwise an old manifest would keep showing
     * the pre-marriage or misspelled name the ticket was almost issued under.
     */
    @Transactional
    public MemberResponse updateMember(String clientId, String memberId, MemberUpdateRequest request) {
        Member member = findMember(clientId, memberId);
        Map<String, String> before = AuditSnapshot.of(member, AUDITED);

        if (request.getRelation() != null) {
            if (member.getType() == MemberType.CLIENT || request.getRelation() == MemberRelation.SELF) {
                throw new IllegalStateException(
                        "The primary member's relation is fixed for the client's life and cannot be changed");
            }
            member.setRelation(request.getRelation());
        }

        boolean renamed = request.getName() != null && !request.getName().equals(member.getName());
        if (renamed) {
            member.setName(request.getName());
        }
        if (request.getEmail() != null) member.setEmail(request.getEmail());
        if (request.getCountryCode() != null) member.setCountryCode(request.getCountryCode());
        if (request.getPhone() != null) member.setPhone(request.getPhone());
        if (request.getDob() != null) member.setDob(request.getDob());
        if (request.getGender() != null) member.setGender(request.getGender());
        if (request.getNationality() != null) member.setNationality(request.getNationality());
        if (request.getPassportNumber() != null) member.setPassportNumber(request.getPassportNumber());
        if (request.getPassportExpiry() != null) member.setPassportExpiry(request.getPassportExpiry());

        member.setModifiedAt(LocalDateTime.now());
        member.setModifiedBy(currentUserId());
        memberRepository.save(member);

        if (renamed) {
            leadMemberRepository.updateMemberNameForMember(member.getMemberId(), member.getName());
        }

        auditService.recordUpdate(AuditEntityType.MEMBER, member.getMemberId(), member.getName(),
                AuditSnapshot.diff(before, AuditSnapshot.of(member, AUDITED)));
        log.info("Member updated: clientId={}, memberId={}, renamed={}", clientId, memberId, renamed);
        return memberMapper.toResponse(member, memberDocumentRepository.findByMemberId(memberId));
    }

    /**
     * Deactivation, not deletion - the member may already appear on a past lead's manifest,
     * and that history has to keep resolving.
     *
     * <p>The primary member cannot be deactivated while the client is active: the partial
     * unique index guarantees exactly one, so removing them would leave the client with no
     * point of contact and no source for its snapshot columns.
     */
    @Transactional
    public MemberResponse updateStatus(String clientId, String memberId, boolean active) {
        Member member = findMember(clientId, memberId);
        if (!active && member.getType() == MemberType.CLIENT) {
            throw new IllegalStateException(
                    "The primary member cannot be deactivated - deactivate the client instead");
        }
        Map<String, String> before = AuditSnapshot.of(member, AUDITED);
        member.setIsActive(active);
        member.setModifiedAt(LocalDateTime.now());
        member.setModifiedBy(currentUserId());
        memberRepository.save(member);
        auditService.recordUpdate(AuditEntityType.MEMBER, member.getMemberId(), member.getName(),
                AuditSnapshot.diff(before, AuditSnapshot.of(member, AUDITED)));
        log.info("Member status updated: clientId={}, memberId={}, active={}", clientId, memberId, active);
        return memberMapper.toResponse(member, memberDocumentRepository.findByMemberId(memberId));
    }

    @Transactional
    public DocumentResponse uploadDocument(String clientId, String memberId, String docType, MultipartFile file) {
        Member member = findMember(clientId, memberId);
        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        String fileKey = fileStorageService.store(tenantId, MEMBER_DOC_CATEGORY, memberId, file);

        MemberDocument doc = MemberDocument.builder()
                .id(UniqueIdResolver.resolve(memberDocumentRepository::existsById))
                .memberId(member.getMemberId())
                .name(file.getOriginalFilename())
                .fileKey(fileKey)
                .docType(docType)
                .contentType(file.getContentType())
                .uploadedAt(LocalDateTime.now())
                .uploadedBy(currentUserId())
                .build();
        memberDocumentRepository.save(doc);
        log.info("Member document uploaded: memberId={}, documentId={}", memberId, doc.getId());
        return toDocumentResponse(doc);
    }

    @Transactional
    public void deleteDocument(String clientId, String memberId, String documentId) {
        findMember(clientId, memberId);
        MemberDocument doc = memberDocumentRepository.findByIdAndMemberId(documentId, memberId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
        fileStorageService.delete(doc.getFileKey());
        memberDocumentRepository.delete(doc);
        log.info("Member document deleted: memberId={}, documentId={}", memberId, documentId);
    }

    @Transactional(readOnly = true)
    public DocumentContent downloadDocument(String clientId, String memberId, String documentId) {
        findMember(clientId, memberId);
        MemberDocument doc = memberDocumentRepository.findByIdAndMemberId(documentId, memberId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
        Resource resource = fileStorageService.retrieve(doc.getFileKey());
        return new DocumentContent(resource, doc.getName(), doc.getContentType());
    }

    /** Not a DTO - never serialized to JSON, only unpacked into a binary response by the controller. */
    public record DocumentContent(Resource resource, String filename, String contentType) {
    }

    /**
     * Resolves a member within a client the caller may access. Scoping the lookup by client id
     * rather than member id alone is what stops a caller reading a colleague's roster by
     * guessing a member id under a client they do own.
     */
    Member findMember(String clientId, String memberId) {
        clientService.findAccessibleClient(clientId);
        return memberRepository.findByMemberIdAndClientId(memberId, clientId)
                .orElseThrow(() -> new IllegalArgumentException("Member not found: " + memberId));
    }

    private String currentUserId() {
        return SecurityContextUtil.getCurrentUserOrThrow().userId();
    }

    private DocumentResponse toDocumentResponse(MemberDocument document) {
        return DocumentResponse.builder()
                .id(document.getId()).name(document.getName())
                .docType(document.getDocType()).uploadedDate(document.getUploadedAt())
                .build();
    }
}
