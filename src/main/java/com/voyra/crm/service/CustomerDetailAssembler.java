package com.voyra.crm.service;

import com.voyra.crm.dto.BookingSummaryResponse;
import com.voyra.crm.dto.CustomerDetailResponse;
import com.voyra.crm.dto.DocumentResponse;
import com.voyra.crm.dto.FamilyMemberResponse;
import com.voyra.crm.dto.InteractionResponse;
import com.voyra.crm.entity.Customer;
import com.voyra.crm.entity.FamilyMember;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.CustomerDocumentRepository;
import com.voyra.crm.repository.CustomerInteractionRepository;
import com.voyra.crm.repository.FamilyMemberDocumentRepository;
import com.voyra.crm.repository.FamilyMemberRepository;
import com.voyra.crm.repository.LeadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** Assembles the Customer 360 detail view - kept separate from CustomerService to avoid a god-class. */
@Service
@RequiredArgsConstructor
public class CustomerDetailAssembler {

    private static final Set<LeadStatus> TERMINAL_STATUSES = Set.of(LeadStatus.BOOKED, LeadStatus.LOST);

    private final BookingRepository bookingRepository;
    private final LeadRepository leadRepository;
    private final com.voyra.crm.repository.CustomerDocumentRepository customerDocumentRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final FamilyMemberDocumentRepository familyMemberDocumentRepository;
    private final CustomerInteractionRepository interactionRepository;

    @Transactional(readOnly = true)
    public CustomerDetailResponse assemble(Customer c) {
        var bookings = bookingRepository.findByCustomerId(c.getId());
        BigDecimal totalSpend = bookings.stream()
                .map(b -> b.getSellingPrice() != null ? b.getSellingPrice() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long activeLeadsCount = leadRepository.countByCustomerIdAndStatusNotIn(c.getId(), TERMINAL_STATUSES);

        List<BookingSummaryResponse> bookingSummaries = bookings.stream()
                .map(b -> BookingSummaryResponse.builder()
                        .id(b.getId()).type(b.getType()).destination(b.getDestination())
                        .journeyDate(b.getJourneyDate()).sellingPrice(b.getSellingPrice())
                        .bookingStatus(b.getBookingStatus()).build())
                .toList();

        List<DocumentResponse> documents = customerDocumentRepository.findByCustomerId(c.getId()).stream()
                .map(d -> DocumentResponse.builder().id(d.getId()).name(d.getName())
                        .docType(d.getDocType()).uploadedDate(d.getUploadedDate()).build())
                .toList();

        List<FamilyMemberResponse> familyMembers = familyMemberRepository.findByCustomerId(c.getId()).stream()
                .map(this::toFamilyMemberResponse)
                .toList();

        List<InteractionResponse> interactions = interactionRepository
                .findByCustomerIdOrderByCreatedDateDesc(c.getId()).stream()
                .map(i -> InteractionResponse.builder().id(i.getId()).authorAgentId(i.getAuthorAgentId())
                        .authorName(i.getAuthorName()).note(i.getNote()).createdDate(i.getCreatedDate()).build())
                .toList();

        return CustomerDetailResponse.builder()
                .id(c.getId()).agentId(c.getAgentId()).agentName(c.getAgentName())
                .name(c.getName()).email(c.getEmail()).countryCode(c.getCountryCode()).phone(c.getPhone())
                .dob(c.getDob()).gender(c.getGender()).city(c.getCity()).country(c.getCountry())
                .nationality(c.getNationality()).passportNumber(c.getPassportNumber())
                .passportExpiry(c.getPassportExpiry()).preferredAirline(c.getPreferredAirline())
                .preferredCabin(c.getPreferredCabin()).status(c.getStatus()).tags(c.getTags())
                .createdDate(c.getCreatedDate())
                .totalSpend(totalSpend)
                .activeLeadsCount(activeLeadsCount)
                .bookings(bookingSummaries)
                .documents(documents)
                .familyMembers(familyMembers)
                .interactions(interactions)
                .build();
    }

    private FamilyMemberResponse toFamilyMemberResponse(FamilyMember member) {
        List<DocumentResponse> docs = familyMemberDocumentRepository.findByFamilyMemberId(member.getId()).stream()
                .map(d -> DocumentResponse.builder().id(d.getId()).name(d.getName())
                        .docType(d.getDocType()).uploadedDate(d.getUploadedDate()).build())
                .toList();
        return FamilyMemberResponse.builder()
                .id(member.getId()).name(member.getName()).relation(member.getRelation())
                .dob(member.getDob()).documents(docs).build();
    }
}
