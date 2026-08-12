package com.voyra.crm.service;

import com.voyra.crm.dto.DocumentResponse;
import com.voyra.crm.dto.FamilyMemberCreateRequest;
import com.voyra.crm.dto.FamilyMemberResponse;
import com.voyra.crm.dto.InteractionCreateRequest;
import com.voyra.crm.dto.InteractionResponse;
import com.voyra.crm.entity.Customer;
import com.voyra.crm.entity.CustomerDocument;
import com.voyra.crm.entity.CustomerInteraction;
import com.voyra.crm.entity.FamilyMember;
import com.voyra.crm.entity.FamilyMemberDocument;
import com.voyra.crm.repository.CustomerDocumentRepository;
import com.voyra.crm.repository.CustomerInteractionRepository;
import com.voyra.crm.repository.FamilyMemberDocumentRepository;
import com.voyra.crm.repository.FamilyMemberRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

/** Family members, documents, and interaction notes - all scoped under a Customer the caller can access. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerSubResourceService {

    private static final String CUSTOMER_DOC_CATEGORY = "customer-documents";
    private static final String FAMILY_DOC_CATEGORY = "family-member-documents";

    private final CustomerService customerService;
    private final FamilyMemberRepository familyMemberRepository;
    private final FamilyMemberDocumentRepository familyMemberDocumentRepository;
    private final CustomerDocumentRepository customerDocumentRepository;
    private final CustomerInteractionRepository interactionRepository;
    private final AuthorResolver authorResolver;
    private final FileStorageService fileStorageService;

    @Transactional
    public FamilyMemberResponse addFamilyMember(String customerId, FamilyMemberCreateRequest request) {
        customerService.findAccessibleCustomer(customerId);
        FamilyMember member = FamilyMember.builder()
                .id(UniqueIdResolver.resolve(familyMemberRepository::existsById))
                .customerId(customerId)
                .name(request.getName())
                .relation(request.getRelation())
                .dob(request.getDob())
                .build();
        familyMemberRepository.save(member);
        log.info("Family member added: customerId={}, familyMemberId={}", customerId, member.getId());
        return FamilyMemberResponse.builder()
                .id(member.getId()).name(member.getName()).relation(member.getRelation())
                .dob(member.getDob()).documents(List.of()).build();
    }

    @Transactional
    public DocumentResponse uploadCustomerDocument(String customerId, String docType, MultipartFile file) {
        Customer customer = customerService.findAccessibleCustomer(customerId);
        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        String fileKey = fileStorageService.store(tenantId, CUSTOMER_DOC_CATEGORY, customerId, file);

        CustomerDocument doc = CustomerDocument.builder()
                .id(UniqueIdResolver.resolve(customerDocumentRepository::existsById))
                .customerId(customer.getId())
                .name(file.getOriginalFilename())
                .fileKey(fileKey)
                .docType(docType)
                .uploadedDate(LocalDateTime.now())
                .build();
        customerDocumentRepository.save(doc);
        log.info("Customer document uploaded: customerId={}, documentId={}", customerId, doc.getId());
        return DocumentResponse.builder().id(doc.getId()).name(doc.getName())
                .docType(doc.getDocType()).uploadedDate(doc.getUploadedDate()).build();
    }

    @Transactional
    public void deleteCustomerDocument(String customerId, String documentId) {
        customerService.findAccessibleCustomer(customerId);
        CustomerDocument doc = customerDocumentRepository.findById(documentId)
                .filter(d -> d.getCustomerId().equals(customerId))
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
        fileStorageService.delete(doc.getFileKey());
        customerDocumentRepository.delete(doc);
        log.info("Customer document deleted: customerId={}, documentId={}", customerId, documentId);
    }

    @Transactional
    public DocumentResponse uploadFamilyMemberDocument(String customerId, String familyMemberId,
                                                         String docType, MultipartFile file) {
        customerService.findAccessibleCustomer(customerId);
        FamilyMember member = familyMemberRepository.findById(familyMemberId)
                .filter(m -> m.getCustomerId().equals(customerId))
                .orElseThrow(() -> new IllegalArgumentException("Family member not found: " + familyMemberId));

        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        String fileKey = fileStorageService.store(tenantId, FAMILY_DOC_CATEGORY, familyMemberId, file);

        FamilyMemberDocument doc = FamilyMemberDocument.builder()
                .id(UniqueIdResolver.resolve(familyMemberDocumentRepository::existsById))
                .familyMemberId(member.getId())
                .name(file.getOriginalFilename())
                .fileKey(fileKey)
                .docType(docType)
                .uploadedDate(LocalDateTime.now())
                .build();
        familyMemberDocumentRepository.save(doc);
        log.info("Family member document uploaded: familyMemberId={}, documentId={}", familyMemberId, doc.getId());
        return DocumentResponse.builder().id(doc.getId()).name(doc.getName())
                .docType(doc.getDocType()).uploadedDate(doc.getUploadedDate()).build();
    }

    @Transactional
    public InteractionResponse addInteraction(String customerId, InteractionCreateRequest request) {
        customerService.findAccessibleCustomer(customerId);
        AuthorResolver.AuthorInfo author = authorResolver.resolveCurrentAuthor();

        CustomerInteraction interaction = CustomerInteraction.builder()
                .id(UniqueIdResolver.resolve(interactionRepository::existsById))
                .customerId(customerId)
                .authorAgentId(author.id())
                .authorName(author.name())
                .note(request.getNote())
                .createdDate(LocalDateTime.now())
                .build();
        interactionRepository.save(interaction);
        log.info("Interaction logged: customerId={}, interactionId={}", customerId, interaction.getId());
        return InteractionResponse.builder()
                .id(interaction.getId()).authorAgentId(author.id()).authorName(author.name())
                .note(interaction.getNote()).createdDate(interaction.getCreatedDate()).build();
    }
}
